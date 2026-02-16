package com.tt4.rider.nuget.actions

import com.tt4.rider.nuget.ui.CredentialsManagerDialog
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.ui.Messages


/**
 * Action to manage NuGet feed credentials
 */
class NuGetCredentialsAction : AnAction() {

    companion object {
        private val logger = thisLogger()
    }

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        logger.info("NuGet Credentials management action triggered")

        try {
            val dialog = CredentialsManagerDialog(project)
            dialog.show()
        } catch (ex: Exception) {
            logger.error("Error opening credentials manager", ex)
            Messages.showErrorDialog(
                project,
                "Failed to open credentials manager: ${ex.message}",
                "NuGet Auto-Fill Error"
            )
        }
    }

    override fun update(e: AnActionEvent) {
        // Always enable the action
        e.presentation.isEnabledAndVisible = true
        e.presentation.text = "Manage NuGet Credentials"
        e.presentation.description = "Manage stored credentials for NuGet feeds"
    }
}
