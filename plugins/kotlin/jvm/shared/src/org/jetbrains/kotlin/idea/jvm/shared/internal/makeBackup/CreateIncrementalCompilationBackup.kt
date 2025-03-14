// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.jvm.shared.internal.makeBackup

import com.intellij.compiler.server.BuildManager
import com.intellij.history.core.RevisionsCollector
import com.intellij.history.core.revisions.Revision.getDifferencesBetween
import com.intellij.history.integration.LocalHistoryImpl
import com.intellij.ide.IdeBundle
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder.Companion.okCancel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.VcsException
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.patch.PatchWriter
import com.intellij.project.stateStore
import com.intellij.util.WaitForProgressToShow
import com.intellij.util.io.ZipUtil
import org.jetbrains.kotlin.idea.jvm.shared.KotlinJvmBundle
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipOutputStream

private class CreateIncrementalCompilationBackup : AnAction(KotlinJvmBundle.message("create.backup.for.debugging.kotlin.incremental.compilation")) {
    companion object {
        const val BACKUP_DIR_NAME = ".backup"
        const val PATCHES_TO_CREATE = 5

        const val PATCHES_FRACTION = .25
        const val LOGS_FRACTION = .05
        const val PROJECT_SYSTEM_FRACTION = .05
        const val ZIP_FRACTION = 1.0 - PATCHES_FRACTION - LOGS_FRACTION - PROJECT_SYSTEM_FRACTION
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project!!
        val projectBaseDir = File(project.baseDir!!.path)
        val backupDir = File(FileUtil.createTempDirectory("makeBackup", null), BACKUP_DIR_NAME)

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(
                project,
                KotlinJvmBundle.message("creating.backup.for.debugging.kotlin.incremental.compilation"),
                true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    createPatches(backupDir, project, indicator)
                    copyLogs(backupDir, indicator)
                    copyProjectSystemDir(backupDir, project, indicator)

                    zipProjectDir(backupDir, project, projectBaseDir, indicator)
                }
            }
        )
    }

    @Suppress("HardCodedStringLiteral")
    private fun createPatches(backupDir: File, project: Project, indicator: ProgressIndicator) {
        runReadAction {
            val localHistoryImpl = LocalHistoryImpl.getInstanceImpl()!!
            val gateway = localHistoryImpl.gateway
            val localHistoryFacade = localHistoryImpl.facade

            val revisions = RevisionsCollector.collect(
                localHistoryFacade,
                gateway.createTransientRootEntry(),
                project.baseDir!!.path,
                project.locationHash,
            )

            var patchesCreated = 0

            val patchesDir = File(backupDir, "patches")
            patchesDir.mkdirs()

            for (rev in revisions) {
                val label = rev.label
                if (label != null && label.startsWith(HISTORY_LABEL_PREFIX)) {
                    val patchFile = File(patchesDir, label.removePrefix(HISTORY_LABEL_PREFIX) + ".patch")

                    indicator.text = KotlinJvmBundle.message("creating.patch.0", patchFile)
                    indicator.fraction = PATCHES_FRACTION * patchesCreated / PATCHES_TO_CREATE

                    val differences = getDifferencesBetween(revisions[0], rev!!)!!
                    val changes = differences.map { d ->
                        Change(d.getLeftContentRevision(gateway), d.getRightContentRevision(gateway))
                    }

                    createPatch(project, changes, patchFile.toPath(), false)

                    if (++patchesCreated >= PATCHES_TO_CREATE) {
                        break
                    }
                }
            }
        }
    }

    @Throws(IOException::class, VcsException::class)
    private fun createPatch(project: Project, changes: List<Change>, file: Path, isReverse: Boolean) {
        val basePath = project.stateStore.projectBasePath
        val patches = IdeaTextPatchBuilder.buildPatch(project, changes, basePath, isReverse, false)
        PatchWriter.writePatches(project, file, basePath, patches, null)
    }

    private fun copyLogs(backupDir: File, indicator: ProgressIndicator) {
        indicator.text = KotlinJvmBundle.message("copying.logs")
        indicator.fraction = PATCHES_FRACTION

        val logsDir = File(backupDir, "logs")
        FileUtil.copyDir(File(PathManager.getLogPath()), logsDir)

        indicator.fraction = PATCHES_FRACTION + LOGS_FRACTION
    }

    private fun copyProjectSystemDir(backupDir: File, project: Project, indicator: ProgressIndicator) {
        indicator.text = KotlinJvmBundle.message("copying.project.s.system.dir")
        indicator.fraction = PATCHES_FRACTION

        val projectSystemDir = File(backupDir, "project-system")
        FileUtil.copyDir(BuildManager.getInstance().getProjectSystemDirectory(project), projectSystemDir)

        indicator.fraction = PATCHES_FRACTION + LOGS_FRACTION + PROJECT_SYSTEM_FRACTION
    }

    private fun zipProjectDir(backupDir: File, project: Project, projectDir: File, indicator: ProgressIndicator) {
        // files and relative paths

        val files = ArrayList<Pair<File, String>>() // files and relative paths
        var totalBytes = 0L

        for (dir in listOf(projectDir, backupDir.parentFile!!)) {
            FileUtil.processFilesRecursively(
                dir,
                /*processor*/ {
                    if (it!!.isFile
                        && !it.name.endsWith(".hprof")
                        && !(it.name.startsWith("make_backup_") && it.name.endsWith(".zip"))
                    ) {

                        indicator.text = KotlinJvmBundle.message("scanning.project.dir.0", it)

                        files.add(Pair(it, FileUtil.getRelativePath(dir, it)!!))
                        totalBytes += it.length()
                    }
                    true
                },
                /*directoryFilter*/ {
                    val name = it!!.name
                    name != ".git" && name != "out"
                }
            )
        }


        val backupFile = File(projectDir, "make_backup_" + SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(Date()) + ".zip")


        val zos = ZipOutputStream(FileOutputStream(backupFile))

        var processedBytes = 0L

        zos.use {
            for ((file, relativePath) in files) {
                indicator.text = KotlinJvmBundle.message("adding.file.to.backup.0", relativePath)
                indicator.fraction = PATCHES_FRACTION + LOGS_FRACTION + processedBytes.toDouble() / totalBytes * ZIP_FRACTION

                ZipUtil.addFileToZip(zos, file, relativePath, null, null)

                processedBytes += file.length()
            }
        }

        FileUtil.delete(backupDir)

        WaitForProgressToShow.runOrInvokeLaterAboveProgress(
            {
                val confirmed =
                    okCancel(
                        KotlinJvmBundle.message("created.backup"),
                        KotlinJvmBundle.message("successfully.created.backup.0", backupFile.absolutePath)
                    )
                    .yesText(RevealFileAction.getActionName(null))
                    .noText(IdeBundle.message("action.close"))
                    .icon(Messages.getInformationIcon())
                    .ask(project)
                if (confirmed) {
                    RevealFileAction.openFile(backupFile)
                }
            }, null, project
        )
    }
}
