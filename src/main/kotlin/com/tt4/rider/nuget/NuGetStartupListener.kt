package com.tt4.rider.nuget

import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.wm.IdeFrame
import java.awt.Window

/**
 * Application listener to initialize NuGet dialog interception when Rider starts
 */
class NuGetStartupListener : ApplicationActivationListener {

    companion object {
        private val logger = thisLogger()
    }

    override fun applicationActivated(ideFrame: IdeFrame) {
        // Initialize the dialog interceptor when the application becomes active
        ApplicationManager.getApplication().invokeLater {
            try {
                logger.info("NuGet Auto-Fill plugin activating...")

                val interceptor = NuGetDialogInterceptor.getInstance()
                val credentialStore = NuGetCredentialsStore.getInstance()

                // Initialize the interceptor
                interceptor.initialize()

                // Log status
                logger.info("NuGet Auto-Fill plugin activated successfully")
                logger.info("Dialog interceptor running: ${interceptor.isRunning()}")
                logger.info(credentialStore.getCredentialSummary())

                // Show welcome message if no credentials stored
                if (!credentialStore.hasStoredCredentials()) {
                    logger.info("No stored credentials found. Use Tools → Manage NuGet Credentials to add feeds.")
                }

            } catch (e: Exception) {
                logger.error("Failed to initialize NuGet Auto-Fill plugin", e)
            }
        }
    }

    override fun applicationDeactivated(ideFrame: IdeFrame) {
        // Optional: Could pause interception when application is not active
        logger.debug("Application deactivated")
    }

    override fun delayedApplicationDeactivated(ideFrame: Window) {
        // Optional: Handle delayed deactivation if needed
        logger.debug("Application delayed deactivation")
    }
}
