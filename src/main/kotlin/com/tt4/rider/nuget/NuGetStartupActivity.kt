package com.tt4.rider.nuget

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Initializes the NuGet dialog interceptor as early as possible in the project lifecycle.
 *
 * [ProjectActivity] fires during project open — before background tasks such as NuGet
 * package restore kick off — ensuring the AWT event listener is in place when the first
 * credential dialog appears.
 *
 * [com.intellij.openapi.application.ApplicationActivationListener] (registered separately
 * in [NuGetStartupListener]) remains as a belt-and-suspenders fallback. The
 * [NuGetDialogInterceptor.initialize] guard makes double-initialization a safe no-op.
 */
class NuGetStartupActivity : ProjectActivity {

    private val logger = thisLogger()

    override suspend fun execute(project: Project) {
        try {
            NuGetDialogInterceptor.getInstance().initialize()
        } catch (e: Exception) {
            logger.error("Failed to initialize NuGet dialog interceptor during project startup", e)
        }
    }
}
