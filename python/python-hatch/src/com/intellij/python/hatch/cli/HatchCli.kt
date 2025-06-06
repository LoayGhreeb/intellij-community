// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.python.hatch.cli

import com.intellij.execution.process.ProcessOutput
import com.intellij.openapi.util.NlsSafe
import com.intellij.platform.eel.provider.utils.sendWholeText
import com.intellij.python.community.execService.ProcessOutputTransformer
import com.intellij.python.hatch.PyHatchBundle
import com.intellij.python.hatch.runtime.HatchConstants
import com.intellij.python.hatch.runtime.HatchRuntime
import com.jetbrains.python.Result
import com.jetbrains.python.errorProcessing.ExecError
import com.jetbrains.python.errorProcessing.PyResult
import io.github.z4kn4fein.semver.Version
import io.github.z4kn4fein.semver.VersionFormatException
import java.nio.file.Path

/**
 * Handles hatch-specific errors, runs [transformer] only on outputs with codes 0 or 1 without tracebacks.
 */
private suspend fun <T> HatchRuntime.executeAndHandleErrors(vararg arguments: String, transformer: ProcessOutputTransformer<T>): Result<T, ExecError> {
  val errorHandlerTransformer: ProcessOutputTransformer<T> = { output ->
    when {
      output.exitCode !in 0..1 -> Result.failure(null)
      output.exitCode == 1 && output.stdout.substringBefore('\n').contains("Traceback (most recent call last)") -> {
        val hatchErrorDescription = output.stdout.split('\n').lastOrNull { it.isNotEmpty() } ?: ""
        Result.failure(hatchErrorDescription)
      }
      else -> transformer.invoke(output)
    }
  }

  return this.execute(*arguments, processOutputTransformer = errorHandlerTransformer)
}

private suspend fun <T> HatchRuntime.executeAndMatch(
  vararg arguments: String,
  expectedOutput: Regex,
  outputContentSupplier: (ProcessOutput) -> String = ProcessOutput::getStdout,
  transformer: (MatchResult) -> Result<T, @NlsSafe String?>,
): Result<T, ExecError> {
  return this.executeAndHandleErrors(*arguments) { processOutput ->
    if (processOutput.exitCode != 0) return@executeAndHandleErrors Result.failure(null)

    val output = outputContentSupplier.invoke(processOutput).replace("\r\n", "\n")
    val matchResult = expectedOutput.matchEntire(output)
    if (matchResult == null) {
      Result.failure(PyHatchBundle.message("python.hatch.cli.error.response.out.of.pattern", expectedOutput.toString()))
    }
    else {
      transformer.invoke(matchResult)
    }
  }
}

sealed class HatchCommand(private val command: Array<String>, protected val runtime: HatchRuntime) {
  @Suppress("unused")
  constructor(command: String, runtime: HatchRuntime) : this(arrayOf(command), runtime)

  protected suspend fun <T> executeAndHandleErrors(vararg arguments: String, transformer: ProcessOutputTransformer<T>): Result<T, ExecError> {
    return runtime.executeAndHandleErrors(*command, *arguments, transformer = transformer)
  }

  protected suspend fun <T> executeAndMatch(vararg arguments: String, expectedOutput: Regex, transformer: (MatchResult) -> Result<T, @NlsSafe String?>): Result<T, ExecError> {
    return runtime.executeAndMatch(*command, *arguments, expectedOutput = expectedOutput, transformer = transformer)
  }
}

class HatchCli(private val runtime: HatchRuntime) {
  /**
   * Build a project
   */
  fun build(): Result<Unit, ExecError> = TODO()

  /**
   * Remove build artifacts
   */
  fun clean(): Result<Unit, ExecError> = TODO()

  /**
   * Manage the config file
   */
  fun config(): HatchConfig = HatchConfig(runtime)

  /**
   * Manage environment dependencies
   */
  fun dep(): HatchDep = HatchDep(runtime)

  /**
   * Manage project environments
   */
  fun env(): HatchEnv = HatchEnv(runtime)

  /**
   * Format and lint source code
   */
  fun fmt(): Result<Unit, ExecError> = TODO()

  /**
   * Create or initialize a project.
   * Returns projects tree structure in human-readable ascii view:
   *
   * {projectName}
   * ├── src
   * │   └── {projectName}
   * │       ├── __about__.py
   * │       └── __init__.py
   * ├── tests
   * │   └── __init__.py
   * ├── LICENSE.txt
   * ├── README.md
   * └── pyproject.toml
   *
   * @param[initExistingProject] Initialize an existing project
   */
  suspend fun new(projectName: String, location: Path? = null, initExistingProject: Boolean = false): PyResult<String> {
    val options = listOf(
      initExistingProject to "--init",
      true to projectName,
      (location != null) to location,
    ).makeOptions()
    return runtime.executeInteractive("new", *options) { eelProcess ->
      if (initExistingProject) {
        eelProcess.stdin.sendWholeText("$projectName\n")
      }
      Result.success("Created")
    }
  }

  /**
   * View project information
   */
  fun project(): HatchProject = HatchProject(runtime)

  /**
   * Publish build artifacts
   */
  fun publish(): Result<Unit, ExecError> = TODO()

  /**
   * Manage Python installations
   */
  fun python(): HatchPython = HatchPython(runtime)

  /**
   * Run commands within project environments
   */
  suspend fun run(envName: String, vararg command: String): Result<String, ExecError> {
    val envRuntime = runtime.withEnv(HatchConstants.AppEnvVars.ENV to envName)
    return envRuntime.executeAndHandleErrors("run", *command) { output ->
      val scenario = output.stderr.trim()
      val content = when {
        output.exitCode == 0 -> {
          val installDetailsContent = output.stdout.replace("─", "").trim()
          val info = installDetailsContent.lines().drop(1).dropLast(2).joinToString("\n")
          "$scenario\n$info"
        }
        else -> scenario
      }
      Result.success(content)
    }
  }

  /**
   * Manage Hatch
   */
  fun self(): HatchSelf = HatchSelf(runtime)

  /**
   * Enter a shell within a project's environment
   */
  fun shell(): Result<Unit, ExecError> = TODO()

  data class HatchStatus(val project: String, val location: Path, val config: Path)

  /**
   * Show information about the current environment
   */
  suspend fun status(): Result<HatchStatus, ExecError> {
    val expectedOutput = """^\[Project] - (.*)\n\[Location] - (.*)\n\[Config] - (.*)\n$""".toRegex()

    return runtime.executeAndMatch("status", expectedOutput = expectedOutput, outputContentSupplier = { it.stderr }) { matchResult ->
      val (project, location, config) = matchResult.destructured
      try {
        Result.success(HatchStatus(project, Path.of(location), Path.of(config)))
      }
      catch (e: Exception) {
        Result.failure(e.localizedMessage)
      }
    }
  }

  /**
   * Run tests
   */
  fun test(): Result<Unit, ExecError> = TODO()

  /**
   * View a project's version.
   *
   * @return Project Version
   */
  suspend fun getVersion(): Result<Version, ExecError> {
    return runtime.executeAndHandleErrors("version") { processOutput ->
      val output = processOutput.takeIf { it.exitCode == 0 }?.stdout?.trim()
                   ?: return@executeAndHandleErrors Result.failure(null)
      try {
        Result.success(Version.parse(output))
      }
      catch (e: VersionFormatException) {
        Result.failure(e.localizedMessage)
      }
    }
  }

  /**
   * Set a project's version.
   *
   * @return OldVersion to NewVersion as Pair
   */
  suspend fun setVersion(desiredVersion: String): PyResult<Pair<Version, Version>> {
    val expectedOutput = """^Old: (.*)\nNew: (.*)\n$""".toRegex()

    return runtime.executeAndMatch("version", desiredVersion, expectedOutput = expectedOutput, outputContentSupplier = { it.stderr }) { matchResult ->
      val (oldVersion, newVersion) = matchResult.destructured
      try {
        Result.success(Version.parse(oldVersion) to Version.parse(newVersion))
      }
      catch (e: VersionFormatException) {
        Result.failure(e.localizedMessage)
      }
    }
  }
}


internal fun List<Pair<Boolean?, *>>.makeOptions(): Array<String> {
  return this.mapNotNull { (flag, option) ->
    when (flag) {
      true -> option.toString()
      else -> null
    }
  }.toTypedArray()
}