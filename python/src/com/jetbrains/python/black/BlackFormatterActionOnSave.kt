// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.jetbrains.python.black

import com.intellij.ide.actionsOnSave.impl.ActionsOnSaveFileDocumentManagerListener.ActionOnSave
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.python.PyBundle
import com.jetbrains.python.black.configuration.BlackFormatterConfiguration
import org.jetbrains.annotations.Nls
import kotlin.coroutines.cancellation.CancellationException

class BlackFormatterActionOnSave : ActionOnSave() {

  companion object {
    val LOG = thisLogger()
  }

  override fun isEnabledForProject(project: Project): Boolean = Registry.`is`("black.formatter.support.enabled")

  override fun processDocuments(project: Project, documents: Array<Document?>) {
    val blackConfig = BlackFormatterConfiguration.getBlackConfiguration(project)
    if (!blackConfig.enabledOnSave) return

    val sdk = blackConfig.getSdk(project)
    if (sdk == null) {
      LOG.warn(PyBundle.message("black.sdk.not.configured.error", project.name))
      return
    }

    formatMultipleDocuments(project, sdk, blackConfig, documents.filterNotNull().toList())
  }

  private fun formatMultipleDocuments(project: Project,
                                      sdk: Sdk,
                                      blackConfig: BlackFormatterConfiguration,
                                      documents: List<Document>) {
    val manager = FileDocumentManager.getInstance()

    val executor = try {
      BlackFormatterExecutor(project, sdk, blackConfig)
    }
    catch (e: Exception) {
      reportFailure(PyBundle.message("black.exception.error.message"), e.localizedMessage, project)
      return
    }

    val descriptors = documents
      .mapNotNull { document -> manager.getFile(document)?.let { document to it } }
      .filter { BlackFormatterUtil.isFileApplicable(it.second) }
      .map { Descriptor(it.first, it.second) }

    runCatching {
      ProgressManager.getInstance().run(
        object : Task.Backgroundable(project, PyBundle.message("black.formatting.with.black"), true) {
          override fun run(indicator: ProgressIndicator) {
            var processedFiles = 0L

            descriptors.forEach { descriptor ->
              processedFiles++
              indicator.fraction = processedFiles / descriptors.size.toDouble()
              indicator.text = PyBundle.message("black.processing.file.name", descriptor.virtualFile.name)
              val request = BlackFormattingRequest.File(descriptor.document.text, descriptor.virtualFile)
              val response = executor.getBlackFormattingResponse(request, BlackFormatterExecutor.BLACK_DEFAULT_TIMEOUT)
              applyChanges(project, descriptor, response)
            }
          }
        }
      )
    }.onFailure { exception ->
      when (exception) {
        is CancellationException -> { /* ignore */ }
        else -> {
          LOG.warn(exception)
          reportFailure(PyBundle.message("black.exception.error.message"), exception.localizedMessage, project)
        }
      }
    }
  }

  private fun applyChanges(project: Project, descriptor: Descriptor, response: BlackFormattingResponse) {
    when (response) {
      is BlackFormattingResponse.Success -> {
        WriteCommandAction.writeCommandAction(project)
          .run<RuntimeException> { descriptor.document.setText(response.formattedText) }
      }
      is BlackFormattingResponse.Failure -> {
        reportFailure(response.title, response.description, project)
      }
      is BlackFormattingResponse.Ignored -> {
        reportIgnored(response.title, response.description, project)
      }
    }
  }

  private fun reportFailure(@Nls title: String, @Nls message: String, project: Project) {
    Notifications.Bus.notify(
      Notification(BlackFormattingService.NOTIFICATION_GROUP_ID,
                   title,
                   message, NotificationType.ERROR), project)
  }

  // [TODO] add `do not show again` option
  private fun reportIgnored(@Nls title: String, @Nls message: String, project: Project) {
    Notifications.Bus.notify(
      Notification(BlackFormattingService.NOTIFICATION_GROUP_ID,
                   title,
                   message, NotificationType.INFORMATION), project)
  }

  private data class Descriptor(val document: Document, val virtualFile: VirtualFile)
}