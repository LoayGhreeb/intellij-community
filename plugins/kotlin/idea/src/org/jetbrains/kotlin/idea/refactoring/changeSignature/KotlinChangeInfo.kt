// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.refactoring.changeSignature

import com.intellij.lang.Language
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.psi.*
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.refactoring.changeSignature.*
import com.intellij.refactoring.util.CanonicalTypes
import com.intellij.usageView.UsageInfo
import com.intellij.util.VisibilityUtil
import org.jetbrains.kotlin.asJava.getRepresentativeLightMethod
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.asJava.unwrapped
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.base.facet.platform.platform
import org.jetbrains.kotlin.idea.base.psi.unquoteKotlinIdentifier
import org.jetbrains.kotlin.idea.caches.resolve.util.getJavaOrKotlinMemberDescriptor
import org.jetbrains.kotlin.idea.j2k.j2k
import org.jetbrains.kotlin.idea.refactoring.changeSignature.KotlinModifiableMethodDescriptor.Kind
import org.jetbrains.kotlin.idea.refactoring.changeSignature.ui.KotlinCallableParameterTableModel
import org.jetbrains.kotlin.idea.refactoring.changeSignature.ui.KotlinChangeSignatureDialog.Companion.getTypeInfo
import org.jetbrains.kotlin.idea.refactoring.changeSignature.usages.KotlinCallableDefinitionUsage
import org.jetbrains.kotlin.idea.refactoring.changeSignature.usages.KotlinCallerUsage
import org.jetbrains.kotlin.idea.resolve.ResolutionFacade
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.JvmAbi
import org.jetbrains.kotlin.name.JvmStandardClassIds.JVM_OVERLOADS_FQ_NAME
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.allChildren
import org.jetbrains.kotlin.psi.psiUtil.isIdentifier
import org.jetbrains.kotlin.psi.psiUtil.quoteIfNeeded
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.types.isError
import org.jetbrains.kotlin.types.typeUtil.isUnit
import org.jetbrains.kotlin.utils.keysToMap

open class KotlinChangeInfo(
    val methodDescriptor: KotlinMethodDescriptor,
    private var name: String = methodDescriptor.name,
    var newReturnTypeInfo: KotlinTypeInfo = KotlinTypeInfo(true, methodDescriptor.baseDescriptor.returnType),
    var newVisibility: DescriptorVisibility = methodDescriptor.visibility,
    parameterInfos: List<KotlinParameterInfo> = methodDescriptor.parameters,
    receiver: KotlinParameterInfo? = methodDescriptor.receiver,
    val context: PsiElement,
    primaryPropagationTargets: Collection<PsiElement> = emptyList(),
    var checkUsedParameters: Boolean = false,
) : KotlinModifiableChangeInfo<KotlinParameterInfo>, UserDataHolder by UserDataHolderBase() {
    private val innerChangeInfo: MutableList<KotlinChangeInfo> = mutableListOf()

    fun registerInnerChangeInfo(changeInfo: KotlinChangeInfo) {
        innerChangeInfo += changeInfo
    }

    private class JvmOverloadSignature(
        val method: PsiMethod,
        val mandatoryParams: Set<KtParameter>,
        val defaultValues: Set<KtExpression>
    ) {
        fun constrainBy(other: JvmOverloadSignature): JvmOverloadSignature {
            return JvmOverloadSignature(
                method,
                mandatoryParams.intersect(other.mandatoryParams),
                defaultValues.intersect(other.defaultValues)
            )
        }
    }

    override fun setType(type: String) {
        newReturnTypeInfo = KtPsiFactory(method.project).createTypeCodeFragment(
                type,
                KotlinCallableParameterTableModel.getTypeCodeFragmentContext(method),
        ).getTypeInfo(true, true)
    }

    private val originalReturnTypeInfo = methodDescriptor.returnTypeInfo
    private val originalReceiverTypeInfo = methodDescriptor.receiver?.originalTypeInfo

    override var receiverParameterInfo: KotlinParameterInfo? = receiver
        set(value) {
            if (value != null && value !in newParameters) {
                newParameters.add(value)
            }
            field = value
        }

    private val newParameters = parameterInfos.toMutableList()

    private val originalPsiMethods = method.toLightMethods()
    private val originalParameters = (method as? KtFunction)?.valueParameters ?: emptyList()
    private val originalSignatures = makeSignatures(originalParameters, originalPsiMethods, { it }, { it.defaultValue })

    private val oldNameToParameterIndex: Map<String, Int> by lazy {
        val map = HashMap<String, Int>()

        val parameters = methodDescriptor.baseDescriptor.valueParameters
        parameters.indices.forEach { i -> map[parameters[i].name.asString()] = i }

        map
    }

    override fun setNewVisibility(visibility: Visibility) {
        newVisibility =  when (visibility) {
            Visibilities.Public -> DescriptorVisibilities.PUBLIC
            Visibilities.Private -> DescriptorVisibilities.PRIVATE
            Visibilities.PrivateToThis -> DescriptorVisibilities.PRIVATE_TO_THIS
            Visibilities.Protected -> DescriptorVisibilities.PROTECTED
            Visibilities.Internal -> DescriptorVisibilities.INTERNAL
            Visibilities.Local -> DescriptorVisibilities.LOCAL
            else -> error("Unknown visibility: $this")
        }
    }

    private val isParameterSetOrOrderChangedLazy: Boolean by lazy {
        val signatureParameters = getNonReceiverParameters()
        methodDescriptor.receiver != receiverParameterInfo ||
                signatureParameters.size != methodDescriptor.parametersCount ||
                signatureParameters.indices.any { i -> signatureParameters[i].oldIndex != i }
    }

    private var isPrimaryMethodUpdated: Boolean = false
    private var javaChangeInfos: List<JavaChangeInfo>? = null
    var originalToCurrentMethods: Map<PsiMethod, PsiMethod> = emptyMap()
        private set

    val parametersToRemove: BooleanArray
        get() {
            val originalReceiver = methodDescriptor.receiver
            val hasReceiver = methodDescriptor.receiver != null
            val receiverShift = if (hasReceiver) 1 else 0

            val toRemove = BooleanArray(receiverShift + methodDescriptor.parametersCount) { true }
            if (hasReceiver) {
                toRemove[0] = receiverParameterInfo == null && originalReceiver !in getNonReceiverParameters()
            }

            for (parameter in newParameters) {
                parameter.oldIndex.takeIf { it >= 0 }?.let { oldIndex ->
                    toRemove[receiverShift + oldIndex] = false
                }
            }

            return toRemove
        }

    fun getOldParameterIndex(oldParameterName: String): Int? = oldNameToParameterIndex[oldParameterName]

    override fun isParameterTypesChanged(): Boolean = true

    override fun isParameterNamesChanged(): Boolean = true

    override fun isParameterSetOrOrderChanged(): Boolean = isParameterSetOrOrderChangedLazy

    fun getNewParametersCount(): Int = newParameters.size

    fun hasAppendedParametersOnly(): Boolean {
        val oldParamCount = originalBaseFunctionDescriptor.valueParameters.size
        return newParameters.asSequence().withIndex().all { (i, p) -> if (i < oldParamCount) p.oldIndex == i else p.isNewParameter }
    }

    override fun getNewParameters(): Array<KotlinParameterInfo> = newParameters.toTypedArray()

    fun getNonReceiverParametersCount(): Int = newParameters.size - (if (receiverParameterInfo != null) 1 else 0)

    fun getNonReceiverParameters(): List<KotlinParameterInfo> {
        methodDescriptor.baseDeclaration.let { if (it is KtProperty || it is KtParameter) return emptyList() }
        return receiverParameterInfo?.let { receiver -> newParameters.filter { it != receiver } } ?: newParameters
    }

    override fun setNewParameter(index: Int, parameterInfo: KotlinParameterInfo) {
        newParameters[index] = parameterInfo
    }

    fun addParameter(parameterInfo: KotlinParameterInfo) {
        addParameter(parameterInfo, -1)
    }

    override fun addParameter(parameterInfo: KotlinParameterInfo, atIndex: Int) {
        if (atIndex >= 0) {
            newParameters.add(atIndex, parameterInfo)
        } else {
            newParameters.add(parameterInfo)
        }
    }

    override fun removeParameter(index: Int) {
        val parameterInfo = newParameters.removeAt(index)
        if (parameterInfo == receiverParameterInfo) {
            receiverParameterInfo = null
        }
    }

    override fun clearParameters() {
        newParameters.clear()
        receiverParameterInfo = null
    }

    fun hasParameter(parameterInfo: KotlinParameterInfo): Boolean =
        parameterInfo in newParameters

    override fun isGenerateDelegate(): Boolean = false

    override fun getNewName(): String = name.takeIf { it != "<no name provided>" }?.quoteIfNeeded() ?: name

    override fun setNewName(value: String) {
        name = value
    }

    override fun isNameChanged(): Boolean = name != methodDescriptor.name

    fun isVisibilityChanged(): Boolean = newVisibility != methodDescriptor.visibility

    override fun getMethod(): PsiElement {
        return methodDescriptor.method
    }

    override fun isReturnTypeChanged(): Boolean = !newReturnTypeInfo.isEquivalentTo(originalReturnTypeInfo)

    fun isReceiverTypeChanged(): Boolean {
        val receiverInfo = receiverParameterInfo ?: return originalReceiverTypeInfo != null
        return originalReceiverTypeInfo == null || !receiverInfo.currentTypeInfo.isEquivalentTo(originalReceiverTypeInfo)
    }

    override fun getLanguage(): Language = KotlinLanguage.INSTANCE

    var propagationTargetUsageInfos: List<UsageInfo> = ArrayList()
        private set

    override var primaryPropagationTargets: Collection<PsiElement> = emptyList()
        set(value) {
            field = value

            val result = LinkedHashSet<UsageInfo>()

            fun add(element: PsiElement) {
                element.unwrapped?.let {
                    val usageInfo = when (it) {
                        is KtNamedFunction, is KtConstructor<*>, is KtClassOrObject ->
                            KotlinCallerUsage(it as KtNamedDeclaration)
                        is PsiMethod ->
                            CallerUsageInfo(it, true, true)
                        else ->
                            return
                    }

                    result.add(usageInfo)
                }
            }

            for (caller in value) {
                add(caller)
                OverridingMethodsSearch.search(caller.getRepresentativeLightMethod() ?: continue).asIterable().forEach(::add)
            }

            propagationTargetUsageInfos = result.toList()
        }

    init {
        this.primaryPropagationTargets = primaryPropagationTargets
    }

    private fun renderReturnTypeIfNeeded(): String? {
        val typeInfo = newReturnTypeInfo
        if (kind != Kind.FUNCTION) return null
        if (typeInfo.type?.isUnit() == true) return null
        return typeInfo.render()
    }

    fun getNewSignature(inheritedCallable: KotlinCallableDefinitionUsage<PsiElement>): String {
        val buffer = StringBuilder()
        val isCustomizedVisibility = newVisibility != DescriptorVisibilities.DEFAULT_VISIBILITY

        if (kind == Kind.PRIMARY_CONSTRUCTOR) {
            buffer.append(newName)

            if (isCustomizedVisibility) {
                buffer.append(' ').append(newVisibility).append(" constructor ")
            }
        } else {
            if (!DescriptorUtils.isLocal(inheritedCallable.originalCallableDescriptor) && isCustomizedVisibility) {
                buffer.append(newVisibility).append(' ')
            }

            buffer.append(if (kind == Kind.SECONDARY_CONSTRUCTOR) KtTokens.CONSTRUCTOR_KEYWORD else KtTokens.FUN_KEYWORD).append(' ')

            if (kind == Kind.FUNCTION) {
                receiverParameterInfo?.let {
                    buffer.append(it.currentTypeInfo.getReceiverTypeText()).append('.')
                }
                buffer.append(newName)
            }
        }

        buffer.append(getNewParametersSignature(inheritedCallable))

        renderReturnTypeIfNeeded()?.let { buffer.append(": ").append(it) }

        return buffer.toString()
    }

    fun getNewParametersSignature(inheritedCallable: KotlinCallableDefinitionUsage<*>): String {
        return "(" + getNewParametersSignatureWithoutParentheses(inheritedCallable) + ")"
    }

    fun getNewParametersSignatureWithoutParentheses(
        inheritedCallable: KotlinCallableDefinitionUsage<*>
    ): String {
        val signatureParameters = getNonReceiverParameters()

        val isLambda = inheritedCallable.declaration is KtFunctionLiteral
        if (isLambda && signatureParameters.size == 1 && !signatureParameters[0].requiresExplicitType(inheritedCallable)) {
            return signatureParameters[0].getDeclarationSignature(0, inheritedCallable).text
        }

        return buildString {
            val indices = signatureParameters.indices
            val lastIndex = indices.last
            indices.forEach { index ->
                val parameter = signatureParameters[index].getDeclarationSignature(index, inheritedCallable)
                if (index == lastIndex) {
                    append(parameter.text)
                } else {
                    val lastCommentsOrWhiteSpaces =
                        parameter.allChildren.toList().reversed().takeWhile { it is PsiComment || it is PsiWhiteSpace }
                    if (lastCommentsOrWhiteSpaces.any { it is PsiComment }) {
                        val commentsText = lastCommentsOrWhiteSpaces.reversed().joinToString(separator = "") { it.text }
                        lastCommentsOrWhiteSpaces.forEach { it.delete() }
                        append("${parameter.text},$commentsText\n")
                    } else {
                        append("${parameter.text}, ")
                    }
                }
            }
        }
    }

    fun renderReceiverType(inheritedCallable: KotlinCallableDefinitionUsage<*>): String? {
        val receiverTypeText = receiverParameterInfo?.currentTypeInfo?.getReceiverTypeText() ?: return null
        val typeSubstitutor = inheritedCallable.typeSubstitutor ?: return receiverTypeText
        val currentBaseFunction = inheritedCallable.baseFunction.currentCallableDescriptor ?: return receiverTypeText
        val receiverType = currentBaseFunction.extensionReceiverParameter!!.type
        if (receiverType.isError) return receiverTypeText
        return receiverType.renderTypeWithSubstitution(typeSubstitutor, receiverTypeText, false)
    }

    fun renderReturnType(inheritedCallable: KotlinCallableDefinitionUsage<*>): String {
        val defaultRendering = newReturnTypeInfo.render()
        val typeSubstitutor = inheritedCallable.typeSubstitutor ?: return defaultRendering
        val currentBaseFunction = inheritedCallable.baseFunction.currentCallableDescriptor ?: return defaultRendering
        val returnType = currentBaseFunction.returnType!!
        if (returnType.isError) return defaultRendering
        return returnType.renderTypeWithSubstitution(typeSubstitutor, defaultRendering, false)
    }

    fun primaryMethodUpdated() {
        isPrimaryMethodUpdated = true
        javaChangeInfos = null

        for (info in innerChangeInfo) {
            info.primaryMethodUpdated()
        }
    }

    private fun <Parameter> makeSignatures(
        parameters: List<Parameter>,
        psiMethods: List<PsiMethod>,
        getPsi: (Parameter) -> KtParameter,
        getDefaultValue: (Parameter) -> KtExpression?
    ): List<JvmOverloadSignature> {
        val defaultValueCount = parameters.count { getDefaultValue(it) != null }
        if (psiMethods.size != defaultValueCount + 1) return emptyList()

        val mandatoryParams = parameters.toMutableList()
        val defaultValues = ArrayList<KtExpression>()
        return psiMethods.map { method ->
            JvmOverloadSignature(method, mandatoryParams.asSequence().map(getPsi).toSet(), defaultValues.toSet()).apply {
                val param = mandatoryParams.removeLast { getDefaultValue(it) != null } ?: return@apply
                defaultValues.add(getDefaultValue(param)!!)
            }
        }
    }

    private fun <T> MutableList<T>.removeLast(condition: (T) -> Boolean): T? {
        val index = indexOfLast(condition)
        return if (index >= 0) removeAt(index) else null
    }

    fun getOrCreateJavaChangeInfos(): List<JavaChangeInfo>? {
        fun initCurrentSignatures(currentPsiMethods: List<PsiMethod>): List<JvmOverloadSignature> {
            val parameterInfoToPsi = methodDescriptor.original.parameters.zip(originalParameters).toMap()
            val dummyParameter = KtPsiFactory(method.project).createParameter("dummy")
            return makeSignatures(
                parameters = newParameters,
                psiMethods = currentPsiMethods,
                getPsi = { parameterInfoToPsi[it] ?: dummyParameter },
                getDefaultValue = { it.defaultValue },
            )
        }

        fun matchOriginalAndCurrentMethods(currentPsiMethods: List<PsiMethod>): Map<PsiMethod, PsiMethod> {
            if (!(isPrimaryMethodUpdated && originalBaseFunctionDescriptor is FunctionDescriptor &&
                        originalBaseFunctionDescriptor.annotations.hasAnnotation(JVM_OVERLOADS_FQ_NAME))
            ) {
                return (originalPsiMethods.zip(currentPsiMethods)).toMap()
            }

            if (originalPsiMethods.isEmpty() || currentPsiMethods.isEmpty()) return emptyMap()

            currentPsiMethods.singleOrNull()?.let { method -> return originalPsiMethods.keysToMap { method } }

            val currentSignatures = initCurrentSignatures(currentPsiMethods)
            return originalSignatures.associateBy({ it.method }) { originalSignature ->
                var constrainedCurrentSignatures = currentSignatures.map { it.constrainBy(originalSignature) }
                val maxMandatoryCount = constrainedCurrentSignatures.maxOf { it.mandatoryParams.size }
                constrainedCurrentSignatures = constrainedCurrentSignatures.filter { it.mandatoryParams.size == maxMandatoryCount }
                val maxDefaultCount = constrainedCurrentSignatures.maxOf { it.defaultValues.size }
                constrainedCurrentSignatures.last { it.defaultValues.size == maxDefaultCount }.method
            }
        }

        /*
         * When primaryMethodUpdated is false, changes to the primary Kotlin declaration are already confirmed, but not yet applied.
         * It means that originalPsiMethod has already expired, but new one can't be created until Kotlin declaration is updated
         * (signified by primaryMethodUpdated being true). It means we can't know actual PsiType, visibility, etc.
         * to use in JavaChangeInfo. However they are not actually used at this point since only parameter count and order matters here
         * So we resort to this hack and pass around "default" type (void) and visibility (package-local)
         */

        fun createJavaChangeInfo(
            originalPsiMethod: PsiMethod,
            currentPsiMethod: PsiMethod,
            newName: String,
            newReturnType: PsiType?,
            newParameters: Array<ParameterInfoImpl>
        ): JavaChangeInfo? {
            if (!newName.unquoteKotlinIdentifier().isIdentifier()) return null

            val newVisibility = if (isPrimaryMethodUpdated)
                VisibilityUtil.getVisibilityModifier(currentPsiMethod.modifierList)
            else
                PsiModifier.PACKAGE_LOCAL

            val propagationTargets = primaryPropagationTargets.asSequence()
                .mapNotNull { it.getRepresentativeLightMethod() }
                .toSet()

            val javaChangeInfo = ChangeSignatureProcessor(
              method.project,
              originalPsiMethod,
              false,
              newVisibility,
              newName,
              CanonicalTypes.createTypeWrapper(newReturnType ?: PsiTypes.voidType()),
              newParameters,
              arrayOf<ThrownExceptionInfo>(),
              propagationTargets,
              emptySet()
            ).changeInfo

            javaChangeInfo.updateMethod(currentPsiMethod)
            return javaChangeInfo
        }

        fun getJavaParameterInfos(
            originalPsiMethod: PsiMethod,
            currentPsiMethod: PsiMethod,
            newParameterList: List<KotlinParameterInfo>
        ): MutableList<ParameterInfoImpl> {
            val defaultValuesToSkip = newParameterList.size - currentPsiMethod.parameterList.parametersCount
            val defaultValuesToRetain = newParameterList.count { it.defaultValue != null } - defaultValuesToSkip
            val oldIndices = newParameterList.map { it.oldIndex }.toIntArray()

            // TODO: Ugly code, need to refactor Change Signature data model
            var defaultValuesRemained = defaultValuesToRetain
            for (param in newParameterList) {
                if (param.isNewParameter || param.defaultValue == null || defaultValuesRemained-- > 0) continue
                newParameterList.asSequence()
                    .withIndex()
                    .filter { it.value.oldIndex >= param.oldIndex }
                    .toList()
                    .forEach { oldIndices[it.index]-- }
            }

            defaultValuesRemained = defaultValuesToRetain
            val oldParameterCount = originalPsiMethod.parameterList.parametersCount
            var indexInCurrentPsiMethod = 0
            return newParameterList.asSequence().withIndex().mapNotNullTo(ArrayList()) map@{ pair ->
                val (i, info) = pair

                if (info.defaultValue != null && defaultValuesRemained-- <= 0) return@map null

                val oldIndex = oldIndices[i]
                val javaOldIndex = when {
                    methodDescriptor.receiver == null -> oldIndex
                    info == methodDescriptor.receiver -> 0
                    oldIndex >= 0 -> oldIndex + 1
                    else -> -1
                }
                if (javaOldIndex >= oldParameterCount) return@map null

                val type = if (isPrimaryMethodUpdated)
                    currentPsiMethod.parameterList.parameters[indexInCurrentPsiMethod++].type
                else
                  PsiTypes.voidType()

                val defaultValue = info.defaultValueForCall ?: info.defaultValue
                ParameterInfoImpl(javaOldIndex, info.name, type, defaultValue?.text ?: "")
            }
        }

        fun createJavaChangeInfoForFunctionOrGetter(
            originalPsiMethod: PsiMethod,
            currentPsiMethod: PsiMethod,
            isGetter: Boolean
        ): JavaChangeInfo? {
            val newParameterList = listOfNotNull(receiverParameterInfo) + getNonReceiverParameters()
            val newJavaParameters = getJavaParameterInfos(originalPsiMethod, currentPsiMethod, newParameterList).toTypedArray()
            val newName = if (isGetter) JvmAbi.getterName(newName) else newName
            return createJavaChangeInfo(originalPsiMethod, currentPsiMethod, newName, currentPsiMethod.returnType, newJavaParameters)
        }

        fun createJavaChangeInfoForSetter(originalPsiMethod: PsiMethod, currentPsiMethod: PsiMethod): JavaChangeInfo? {
            val newJavaParameters = getJavaParameterInfos(originalPsiMethod, currentPsiMethod, listOfNotNull(receiverParameterInfo))
            val oldIndex = if (methodDescriptor.receiver != null) 1 else 0
            val parameters = currentPsiMethod.parameterList.parameters
            if (isPrimaryMethodUpdated) {
                val newIndex = if (receiverParameterInfo != null) 1 else 0
                val setterParameter = parameters[newIndex]
                newJavaParameters.add(ParameterInfoImpl(oldIndex, setterParameter.name, setterParameter.type))
            } else {
                if (receiverParameterInfo != null) {
                    if (newJavaParameters.isEmpty()) {
                        newJavaParameters.add(ParameterInfoImpl(oldIndex, "receiver", PsiTypes.voidType()))
                    }
                }
                if (oldIndex < parameters.size) {
                    val setterParameter = parameters[oldIndex]
                    newJavaParameters.add(ParameterInfoImpl(oldIndex, setterParameter.name, setterParameter.type))
                }
            }

            val newName = JvmAbi.setterName(newName)
            return createJavaChangeInfo(originalPsiMethod, currentPsiMethod, newName, PsiTypes.voidType(), newJavaParameters.toTypedArray())
        }

        if (!(method.containingFile as KtFile).platform.isJvm()) return null

        if (javaChangeInfos == null) {
            val method = method
            originalToCurrentMethods = matchOriginalAndCurrentMethods(method.toLightMethods())
            javaChangeInfos = originalToCurrentMethods.entries.mapNotNull {
                val (originalPsiMethod, currentPsiMethod) = it

                when (method) {
                    is KtFunction, is KtClassOrObject ->
                        createJavaChangeInfoForFunctionOrGetter(originalPsiMethod, currentPsiMethod, false)

                    is KtProperty, is KtParameter -> {
                        val accessorName = originalPsiMethod.name
                        when {
                            JvmAbi.isGetterName(accessorName) ->
                                createJavaChangeInfoForFunctionOrGetter(originalPsiMethod, currentPsiMethod, true)
                            JvmAbi.isSetterName(accessorName) ->
                                createJavaChangeInfoForSetter(originalPsiMethod, currentPsiMethod)
                            else -> null
                        }
                    }

                    else -> null
                }
            }
        }

        return javaChangeInfos
    }
}

val KotlinChangeInfo.originalBaseFunctionDescriptor: CallableDescriptor
    get() = methodDescriptor.baseDescriptor

val KotlinChangeInfo.kind: Kind get() = methodDescriptor.kind

val KotlinChangeInfo.oldName: String?
    get() = (methodDescriptor.method as? KtFunction)?.name

fun KotlinChangeInfo.getAffectedCallables(): Collection<UsageInfo> = methodDescriptor.affectedCallables + propagationTargetUsageInfos

fun ChangeInfo.toJetChangeInfo(
    originalChangeSignatureDescriptor: KotlinMethodDescriptor,
    resolutionFacade: ResolutionFacade
): KotlinChangeInfo {
    val method = method as PsiMethod

    val functionDescriptor = method.getJavaOrKotlinMemberDescriptor(resolutionFacade) as CallableDescriptor
    val parameterDescriptors = functionDescriptor.valueParameters

    //noinspection ConstantConditions
    val originalParameterDescriptors = originalChangeSignatureDescriptor.baseDescriptor.valueParameters

    val newParameters = newParameters.withIndex().map { pair ->
        val (i, info) = pair
        val oldIndex = info.oldIndex
        val currentType = parameterDescriptors[i].type

        val defaultValueText = info.defaultValue
        val defaultValueExpr =
            when {
                info is KotlinAwareJavaParameterInfoImpl -> info.kotlinDefaultValue
                language.`is`(JavaLanguage.INSTANCE) && !defaultValueText.isNullOrEmpty() -> {
                    PsiElementFactory.getInstance(method.project).createExpressionFromText(defaultValueText, null).j2k()
                }

                else -> null
            }

        val parameterType = if (oldIndex >= 0) originalParameterDescriptors[oldIndex].type else currentType
        val originalKtParameter = originalParameterDescriptors.getOrNull(oldIndex)?.source?.getPsi() as? KtParameter
        val valOrVar = originalKtParameter?.valOrVarKeyword?.toValVar() ?: KotlinValVar.None
        KotlinParameterInfo(
            callableDescriptor = functionDescriptor,
            originalIndex = oldIndex,
            name = info.name,
            originalTypeInfo = KotlinTypeInfo(false, parameterType),
            defaultValueForCall = defaultValueExpr,
            valOrVar = valOrVar
        ).apply {
            currentTypeInfo = KotlinTypeInfo(false, currentType)
        }
    }

    return KotlinChangeInfo(
        originalChangeSignatureDescriptor,
        newName,
        KotlinTypeInfo(true, functionDescriptor.returnType),
        functionDescriptor.visibility,
        newParameters,
        null,
        method
    )
}
