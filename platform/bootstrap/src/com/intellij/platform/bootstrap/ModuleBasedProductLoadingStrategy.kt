// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.platform.bootstrap

import com.intellij.ide.plugins.*
import com.intellij.idea.AppMode
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.platform.runtime.product.IncludedRuntimeModule
import com.intellij.platform.runtime.product.PluginModuleGroup
import com.intellij.platform.runtime.product.ProductMode
import com.intellij.platform.runtime.product.RuntimeModuleLoadingRule
import com.intellij.platform.runtime.product.impl.IncludedRuntimeModuleImpl
import com.intellij.platform.runtime.product.impl.ServiceModuleMapping
import com.intellij.platform.runtime.product.serialization.ProductModulesSerialization
import com.intellij.platform.runtime.repository.MalformedRepositoryException
import com.intellij.platform.runtime.repository.RuntimeModuleDescriptor
import com.intellij.platform.runtime.repository.RuntimeModuleId
import com.intellij.platform.runtime.repository.RuntimeModuleRepository
import com.intellij.platform.runtime.repository.impl.RuntimeModuleRepositoryImpl
import com.intellij.platform.runtime.repository.serialization.RuntimeModuleRepositorySerialization
import com.intellij.util.PlatformUtils
import com.intellij.util.lang.PathClassLoader
import com.intellij.util.lang.ZipEntryResolverPool
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension

internal class ModuleBasedProductLoadingStrategy(internal val moduleRepository: RuntimeModuleRepository) : ProductLoadingStrategy() {
  private val currentMode by lazy {
    val currentModeId = System.getProperty(PLATFORM_PRODUCT_MODE_PROPERTY, ProductMode.MONOLITH.id)
    val currentMode = ProductMode.entries.find { it.id == currentModeId }
    if (currentMode == null) {
      error("Unknown mode '$currentModeId' specified in '$PLATFORM_PRODUCT_MODE_PROPERTY' system property")
    }
    currentMode
  }
  
  private val productModules by lazy {
    val rootModuleName = System.getProperty(PLATFORM_ROOT_MODULE_PROPERTY)
    if (rootModuleName == null) {
      error("'$PLATFORM_ROOT_MODULE_PROPERTY' system property is not specified")
    }

    val rootModule = moduleRepository.getModule(RuntimeModuleId.module(rootModuleName))
    val productModulesPath = "META-INF/$rootModuleName/product-modules.xml"
    val moduleGroupStream = rootModule.readFile(productModulesPath)
    if (moduleGroupStream == null) {
      error("$productModulesPath is not found in '$rootModuleName' module")
    }
    ProductModulesSerialization.loadProductModules(moduleGroupStream, productModulesPath, currentMode, moduleRepository)
  }
  
  override val currentModeId: String
    get() = currentMode.id

  override fun addMainModuleGroupToClassPath(bootstrapClassLoader: ClassLoader) {
    val mainGroupClassPath = productModules.mainModuleGroup.includedModules.flatMapTo(LinkedHashSet()) {
      it.moduleDescriptor.resourceRootPaths
    }
    val classPath = (bootstrapClassLoader as PathClassLoader).classPath
    logger<ModuleBasedProductLoadingStrategy>().info("New classpath roots:\n${(mainGroupClassPath - classPath.baseUrls.toSet()).joinToString("\n")}")
    classPath.addFiles(mainGroupClassPath)
  }

  override fun loadPluginDescriptors(
    scope: CoroutineScope,
    loadingContext: PluginDescriptorLoadingContext,
    customPluginDir: Path,
    bundledPluginDir: Path?,
    isUnitTestMode: Boolean,
    isRunningFromSources: Boolean,
    zipPool: ZipEntryResolverPool,
    mainClassLoader: ClassLoader,
  ): Deferred<List<DiscoveredPluginsList>> {
    val platformPrefix = PlatformUtils.getPlatformPrefix()
    val isInDevServerMode = AppMode.isDevServer()
    val pathResolver = ClassPathXmlPathResolver(mainClassLoader, isRunningFromSources = isRunningFromSources && !isInDevServerMode)
    val useCoreClassLoader = pathResolver.isRunningFromSources ||
                             platformPrefix.startsWith("CodeServer") ||
                             java.lang.Boolean.getBoolean("idea.force.use.core.classloader")
    val (corePlugin, _) = scope.loadCorePlugin(platformPrefix, isInDevServerMode, isUnitTestMode, isRunningFromSources, loadingContext, pathResolver, useCoreClassLoader, mainClassLoader)
    val custom = loadCustomPluginDescriptors(scope, customPluginDir, loadingContext, zipPool)
    val bundled = loadBundledPluginDescriptors(scope, loadingContext, zipPool)
    return scope.async {
      listOfNotNull(
        corePlugin.await()?.let { DiscoveredPluginsList(listOf(it), PluginsSourceContext.Product) },
        custom.await(),
        bundled.await(),
      )
    }
  }

  private fun loadBundledPluginDescriptors(
    scope: CoroutineScope,
    context: PluginDescriptorLoadingContext,
    zipFilePool: ZipEntryResolverPool,
  ): Deferred<DiscoveredPluginsList> {
    val mainGroupModulesSet = productModules.mainModuleGroup.includedModules.mapTo(HashSet()) { it.moduleDescriptor.moduleId }
    val mainGroupResourceRootSet = productModules.mainModuleGroup.includedModules.flatMapTo(HashSet()) { it.moduleDescriptor.resourceRootPaths }
    val serviceModuleMappingDeferred = scope.async { 
      ServiceModuleMapping.buildMapping(productModules)
    }
    val bundled = productModules.bundledPluginModuleGroups.map { moduleGroup ->
      scope.async {
        if (moduleGroup.includedModules.none { it.moduleDescriptor.moduleId in mainGroupModulesSet }) {
          val serviceModuleMapping = serviceModuleMappingDeferred.await()
          loadPluginDescriptorFromRuntimeModule(moduleGroup, context, zipFilePool, serviceModuleMapping, mainGroupResourceRootSet,
                                                isBundled = true, pluginDir = null)
        }
        else {
          /* todo: intellij.performanceTesting.async plugin has different distributions for different IDEs, in some IDEs it has dependencies 
             on 'intellij.profiler.common' and other module from the platform, in other IDEs it includes them as its own content. In the
             latter case we currently cannot run it using the modular loader, because these modules will be loaded twice. */
          logger<ModuleBasedProductLoadingStrategy>().debug("Skipped $moduleGroup: ${moduleGroup.includedModules}")
          null
        }
      }
    }
    return scope.async { DiscoveredPluginsList(bundled.awaitAll().filterNotNull(), PluginsSourceContext.Bundled) }
  }

  private fun loadCustomPluginDescriptors(
    scope: CoroutineScope,
    customPluginDir: Path,
    context: PluginDescriptorLoadingContext,
    zipFilePool: ZipEntryResolverPool,
  ): Deferred<DiscoveredPluginsList> {
    if (!Files.isDirectory(customPluginDir)) {
      return CompletableDeferred(DiscoveredPluginsList(emptyList(), PluginsSourceContext.Custom))
    }
    val deferredDescriptors = ArrayList<Deferred<IdeaPluginDescriptorImpl?>>()
    Files.newDirectoryStream(customPluginDir).use { dirStream ->
      val additionalRepositoryPaths = ArrayList<Path>()
      dirStream.forEach { file ->
        val moduleRepository = file.resolve("module-descriptors.jar")
        if (moduleRepository.exists()) {
          additionalRepositoryPaths.add(moduleRepository)
        }
        else {
          deferredDescriptors.add(scope.async {
            loadDescriptorFromFileOrDir(
              file = file,
              loadingContext = context,
              pool = zipFilePool,
            )
          })
        }
      }
      deferredDescriptors.addAll(loadPluginDescriptorsFromAdditionalRepositories(scope, additionalRepositoryPaths, context, zipFilePool))
    }
    return scope.async { DiscoveredPluginsList(deferredDescriptors.awaitAll().filterNotNull(), PluginsSourceContext.Custom) }
  }

  private fun loadPluginDescriptorsFromAdditionalRepositories(scope: CoroutineScope,
                                                              repositoryPaths: List<Path>,
                                                              context: PluginDescriptorLoadingContext,
                                                              zipFilePool: ZipEntryResolverPool): Collection<Deferred<IdeaPluginDescriptorImpl?>> {
    val repositoriesByPaths = scope.async {
      val repositoriesByPaths = repositoryPaths.associateWith {
        try {
          RuntimeModuleRepositorySerialization.loadFromJar(it)
        }
        catch (e: MalformedRepositoryException) {
          logger<ModuleBasedProductLoadingStrategy>().warn("Failed to load module repository for a custom plugin: $e", e)
          null
        } 
      }
      (moduleRepository as RuntimeModuleRepositoryImpl).loadAdditionalRepositories(repositoriesByPaths.values.filterNotNull())
      repositoriesByPaths
    }
    return repositoryPaths.map { path -> 
      scope.async { 
        val repositoryDataMap = repositoriesByPaths.await()
        val repositoryData = repositoryDataMap[path] ?: return@async null
        val mainModuleId = repositoryData.mainPluginModuleId ?: return@async null
        try {
          val mainModule = moduleRepository.getModule(RuntimeModuleId.raw(mainModuleId))
          /* 
            It would be probably better to reuse PluginModuleGroup here, and load information about additional modules from plugin.xml. 
            However, currently this won't work because plugin model v2 requires that there is an XML configuration file for each module
            mentioned in the <content> tag, but in the test plugins we have modules without configuration files. 
          */
          val descriptors = ArrayList<RuntimeModuleDescriptor>()
          descriptors.add(mainModule)
          repositoryData.allIds.asSequence()
            .filter { it != mainModuleId }
            .mapTo(descriptors) { moduleRepository.getModule(RuntimeModuleId.raw(it)) }
          val moduleGroup = CustomPluginModuleGroup(descriptors, mainModule)
          loadPluginDescriptorFromRuntimeModule(
            pluginModuleGroup = moduleGroup,
            context = context,
            zipFilePool = zipFilePool,
            serviceModuleMapping = null,
            mainGroupResourceRootSet = emptySet(),
            isBundled = false,
            pluginDir = path.parent,
          )
        }
        catch (t: Throwable) {
          logger<ModuleBasedProductLoadingStrategy>().warn("Failed to load custom plugin '$mainModuleId': $t", t)
          null
        }
      }
    }
  }

  private fun loadPluginDescriptorFromRuntimeModule(
    pluginModuleGroup: PluginModuleGroup,
    context: PluginDescriptorLoadingContext,
    zipFilePool: ZipEntryResolverPool,
    serviceModuleMapping: ServiceModuleMapping?,
    mainGroupResourceRootSet: Set<Path>,
    isBundled: Boolean,
    pluginDir: Path?,
  ): IdeaPluginDescriptorImpl? {
    val mainResourceRoot = pluginModuleGroup.mainModule.resourceRootPaths.singleOrNull()
    if (mainResourceRoot == null) {
      thisLogger().warn(
        "Main plugin module must have single resource root, so '${pluginModuleGroup.mainModule.moduleId.stringId}'" +
        " with roots ${pluginModuleGroup.mainModule.resourceRootPaths} won't be loaded"
      )
      return null
    }

    val includedModules = pluginModuleGroup.includedModules
    val allResourceRoots = includedModules.flatMapTo(LinkedHashSet()) { it.moduleDescriptor.resourceRootPaths }
    val additionalServiceModules = serviceModuleMapping?.getAdditionalModules(pluginModuleGroup) ?: collectRequiredLibraryModules(pluginModuleGroup)
    if (additionalServiceModules.isNotEmpty()) {
      thisLogger().debug { "Additional modules will be added to classpath of $pluginModuleGroup: $additionalServiceModules" }
      additionalServiceModules.flatMapTo(allResourceRoots) {
        // resource roots from plugin modules shouldn't intersect with main group modules, but currently they can: IJPL-671
        it.resourceRootPaths - mainGroupResourceRootSet   
      }
    }
    val allResourceRootsList = allResourceRoots.toList()

    val descriptor = if (Files.isDirectory(mainResourceRoot)) {
      val fallbackResolver = PluginXmlPathResolver(allResourceRootsList.filter { it.extension == "jar" }, zipFilePool)
      val resolver = ModuleBasedPluginXmlPathResolver(includedModules, pluginModuleGroup.optionalModuleIds, fallbackResolver)
      loadDescriptorFromDir(mainResourceRoot, context, zipFilePool, resolver, isBundled = isBundled, pluginDir = pluginDir)
        .also { descriptor ->
          descriptor?.content?.modules?.forEach { module ->
            val requireDescriptor = module.requireDescriptor()
            if (requireDescriptor.packagePrefix == null) {
              val moduleName = requireDescriptor.moduleName
              if (moduleName != null) {
                requireDescriptor.jarFiles = moduleRepository.getModule(RuntimeModuleId.module(moduleName)).resourceRootPaths
              }
            }
          }
        }
    }
    else {
      val defaultResolver = PluginXmlPathResolver(allResourceRootsList, zipFilePool)
      val pathResolver =
        if (allResourceRootsList.size == 1) defaultResolver
        else ModuleBasedPluginXmlPathResolver(includedModules, pluginModuleGroup.optionalModuleIds, defaultResolver)
      val pluginDir = pluginDir ?: mainResourceRoot.parent.parent
      loadDescriptorFromJar(mainResourceRoot, context, zipFilePool, pathResolver, isBundled = isBundled, pluginDir = pluginDir)
    }
    val modulesWithJarFiles = descriptor?.content?.modules?.flatMap { moduleItem ->
      val jarFiles = moduleItem.requireDescriptor().jarFiles
      if (moduleItem.loadingRule != ModuleLoadingRule.EMBEDDED && jarFiles != null) jarFiles else emptyList()
    }
    descriptor?.jarFiles = allResourceRootsList.filter { modulesWithJarFiles == null || it !in modulesWithJarFiles }
    return descriptor
  }

  /* TODO: reuse ServiceModuleMapping instead */
  private fun collectRequiredLibraryModules(pluginModuleGroup: PluginModuleGroup): Set<RuntimeModuleDescriptor> {
    val includedInPlatform = productModules.mainModuleGroup.includedModules.mapTo(HashSet()) { it.moduleDescriptor }

    return pluginModuleGroup.includedModules.flatMapTo(LinkedHashSet()) { includedModule ->
      includedModule.moduleDescriptor.dependencies.filter { dependency ->
        dependency.moduleId.stringId.startsWith(RuntimeModuleId.LIB_NAME_PREFIX) && dependency !in includedInPlatform
      }
    }
  }

  override fun isOptionalProductModule(moduleName: String): Boolean =
    productModules.mainModuleGroup.optionalModuleIds.contains(RuntimeModuleId.raw(moduleName))

  override fun findProductContentModuleClassesRoot(moduleName: String, moduleDir: Path): Path? {
    val resolvedModule = moduleRepository.resolveModule(RuntimeModuleId.module(moduleName)).resolvedModule
    if (resolvedModule == null) {
      // https://youtrack.jetbrains.com/issue/CPP-38280
      // we log here, as only for JetBrainsClient it is expected that some module is not resolved
      thisLogger().debug("Skip loading product content module $moduleName because its classes root isn't present")
      return null
    }

    val paths = resolvedModule.resourceRootPaths
    return paths.singleOrNull() 
           ?: error("Content modules are supposed to have only one resource root, but $moduleName have multiple: $paths")
  }
}

private class CustomPluginModuleGroup(moduleDescriptors: List<RuntimeModuleDescriptor>,
                                      override val mainModule: RuntimeModuleDescriptor) : PluginModuleGroup {
  private val includedModules = moduleDescriptors.map { IncludedRuntimeModuleImpl(it, RuntimeModuleLoadingRule.REQUIRED) } 
  override fun getIncludedModules(): List<IncludedRuntimeModule> = includedModules 
  override fun getOptionalModuleIds(): Set<RuntimeModuleId> = emptySet()
}

private const val PLATFORM_ROOT_MODULE_PROPERTY = "intellij.platform.root.module"
private const val PLATFORM_PRODUCT_MODE_PROPERTY = "intellij.platform.product.mode"
