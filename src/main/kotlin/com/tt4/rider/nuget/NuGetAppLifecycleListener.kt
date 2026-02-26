package com.tt4.rider.nuget

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.diagnostic.thisLogger

/**
 * Registers the NuGet dialog interceptor at the earliest possible point in the application
 * lifecycle — before any project begins loading.
 *
 * [AppLifecycleListener.appFrameCreated] fires when the IDE frame is first created, before
 * any project opens. This guarantees the AWT event listener is in place before Rider's
 * .NET backend starts the solution and triggers NuGet auto-restore, which can show a
 * credential dialog during the very first milliseconds of project load.
 *
 * [NuGetDialogInterceptor.initialize] is called synchronously (no invokeLater) so the
 * listener is active before any subsequent EDT event can arrive.
 *
 * [NuGetStartupActivity] (ProjectActivity) and [NuGetStartupListener]
 * (ApplicationActivationListener) remain registered as belt-and-suspenders fallbacks.
 * The [NuGetDialogInterceptor.initialize] guard makes any redundant call a fast no-op.
 */
class NuGetAppLifecycleListener : AppLifecycleListener {

    private val logger = thisLogger()

    override fun appFrameCreated(commandLineArgs: List<String>) {
        try {
            NuGetDialogInterceptor.getInstance().initialize()
        } catch (e: Exception) {
            logger.error("Failed to initialize NuGet dialog interceptor on app frame created", e)
        }
    }
}
