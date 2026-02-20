package com.tt4.rider.nuget

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import java.awt.*
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import javax.swing.*

/**
 * Service that intercepts NuGet credential dialogs and automatically fills them
 */
@Service
class NuGetDialogInterceptor : Disposable {

    companion object {
        fun getInstance(): NuGetDialogInterceptor {
            return ApplicationManager.getApplication().getService(NuGetDialogInterceptor::class.java)
        }

        private val logger = thisLogger()

        /** Regex matching NuGet feed index.json URLs — exposed for testing */
        internal val URL_PATTERN = Regex("""https?://[^\s<>"]*?/index\.json(?=[\s<>"]|$)""")

        /** Pure title-matching logic — exposed for testing */
        internal fun matchesCredentialTitle(title: String): Boolean {
            val credentialPatterns = listOf(
                "enter credentials",
                "authentication required",
                "authorization required",
                "login required",
                "credentials for",
                "credentials",
                "sign in",
                "log in",
                "authenticate",
                "azure devops",
                "personal access token"
            )
            if (credentialPatterns.any { pattern -> title.contains(pattern, ignoreCase = true) }) return true

            val nugetPatterns = listOf(
                "nuget authentication",
                "nuget credentials",
                "package source credentials"
            )
            return nugetPatterns.any { pattern -> title.contains(pattern, ignoreCase = true) }
        }
    }

    private val credentialStore = NuGetCredentialsStore.getInstance()
    private val eventListener = NuGetDialogEventListener()
    private var isInitialized = false

    /**
     * Initialize the dialog interception
     */
    fun initialize() {
        if (isInitialized) {
            logger.debug("Dialog interceptor already initialized")
            return
        }

        try {
            // Register AWT event listener for dialog detection
            Toolkit.getDefaultToolkit().addAWTEventListener(
                eventListener,
                AWTEvent.WINDOW_EVENT_MASK
            )

            isInitialized = true
            logger.info("NuGet dialog interceptor initialized successfully")
            logger.info(credentialStore.getCredentialSummary())

        } catch (e: Exception) {
            logger.error("Failed to initialize NuGet dialog interceptor", e)
        }
    }

    /**
     * AWT Event listener for dialog detection
     */
    private inner class NuGetDialogEventListener : AWTEventListener {

        override fun eventDispatched(event: AWTEvent) {
            if (event is WindowEvent && event.id == WindowEvent.WINDOW_OPENED) {
                val window = event.window

                // Process in EDT to avoid threading issues
                SwingUtilities.invokeLater {
                    try {
                        if (isNuGetCredentialDialog(window)) {
                            logger.info("Detected NuGet credential dialog: ${window.javaClass.simpleName}")
                            processNuGetDialog(window)
                        }
                    } catch (e: Exception) {
                        logger.error("Error processing potential NuGet dialog", e)
                    }
                }
            }
        }
    }

    /**
     * Enhanced dialog detection with multiple strategies.
     * Always logs the window title at DEBUG level so missed dialogs can be diagnosed via the IDE log.
     */
    private fun isNuGetCredentialDialog(window: Window): Boolean {
        val title = getWindowTitle(window)
        logger.debug("Window opened: title='$title', class='${window.javaClass.simpleName}'")
        return when {
            checkByTitle(window) -> {
                logger.debug("Dialog matched by title: '$title'")
                true
            }
            checkByContent(window) -> {
                logger.info("Dialog matched by content (title '$title' not in known patterns) — consider adding this title to matchesCredentialTitle()")
                true
            }
            else -> false
        }
    }

    /**
     * Fallback detection: checks whether the window contains both a JPasswordField
     * and a JLabel with a NuGet feed URL. This catches credential dialogs whose titles
     * don't match any known pattern (e.g. custom proxy dialogs, non-English Rider).
     * Note: Window always extends Container in Swing, so we cast directly.
     */
    private fun checkByContent(window: Window): Boolean {
        val hasPasswordField = containsPasswordField(window)
        val hasNuGetUrl = findUrlInLabels(window) != null
        return hasPasswordField && hasNuGetUrl
    }

    /**
     * Recursively checks whether a container contains a JPasswordField component.
     */
    private fun containsPasswordField(container: Container): Boolean {
        for (component in container.components) {
            if (component is JPasswordField) return true
            if (component is Container && containsPasswordField(component)) return true
        }
        return false
    }

    /**
     * Process detected NuGet dialog
     */
    private fun processNuGetDialog(window: Window) {
        try {
            // Extract feed information
            val feedInfo = extractFeedInformation(window)
            logger.debug("Extracted feed info: url=${feedInfo.url}, name=${feedInfo.name}")

            // Get stored credentials
            val credentials = credentialStore.getCredentials(feedInfo.url)

            if (credentials != null && credentialStore.isFeedEnabled(feedInfo.url)) {
                logger.info("Auto-filling credentials for feed: ${feedInfo.url}")
                fillCredentials(window, credentials)
            } else {
                logger.debug("No credentials found or feed disabled for: ${feedInfo.url}")
            }

        } catch (e: Exception) {
            logger.error("Error processing NuGet dialog", e)
        }
    }

    /**
     * Check dialog by window title
     */
    private fun checkByTitle(window: Window): Boolean {
        val title = getWindowTitle(window) ?: return false
        return matchesCredentialTitle(title)
    }

    /**
     * Feed information extracted from dialog
     */
    data class FeedInfo(
        val url: String? = null,
        val name: String? = null
    )

    /**
     * Extract feed information from dialog
     */
    private fun extractFeedInformation(window: Window): FeedInfo {

        // Search for URL in labels (most common location)
        val url: String? = findUrlInLabels(window)

        return FeedInfo(url, null)
    }

    private fun findUrlInLabels(container: Container): String? {
        val urlPattern = URL_PATTERN

        // Check immediate children
        for (component in container.components) {
            if (component is JLabel) {
                component.text?.let { text ->
                    urlPattern.find(text)?.let { match ->
                        return match.value
                    }
                }
            }
        }

        // Check one level deeper
        for (component in container.components) {
            if (component is Container) {
                for (child in component.components) {
                    if (child is JLabel) {
                        child.text?.let { text ->
                            urlPattern.find(text)?.let { match ->
                                return match.value
                            }
                        }
                    }
                }
            }
        }

        return null
    }

    /**
     * Fill credentials in the dialog
     */
    private fun fillCredentials(window: Window, credentials: NuGetCredentials) {
        var filledFields = 0

        val result = findCredentialFields(window)
        val usernameField = result.first
        val passwordField = result.second

        // Fill the fields
        usernameField?.let { field ->
            field.text = credentials.username
            field.caretPosition = field.text.length
            filledFields++
            logger.debug("Filled username field with: ${credentials.username}")
        }

        passwordField?.let { field ->
            field.text = credentials.password
            field.caretPosition = field.password.size
            filledFields++
            logger.debug("Filled password field")
        }

        logger.info("Filled $filledFields credential fields")

        // Auto-submit if configured and both fields filled
        if (filledFields >= 2 && credentials.autoSubmit) {
            SwingUtilities.invokeLater {
                submitDialog(window)
            }
        }
    }

    private fun findCredentialFields(container: Container): Pair<JTextField?, JPasswordField?> {
        var usernameField: JTextField? = null
        var passwordField: JPasswordField? = null

        fun searchComponents(comp: Component) {
            when (comp) {
                is JPasswordField -> {
                    if (passwordField == null) passwordField = comp
                }

                is JTextField -> {
                    if (usernameField == null) usernameField = comp
                }

                is Container -> {
                    // Only recurse into containers, skip leaf components
                    comp.components?.forEach { searchComponents(it) }
                }
            }

            // Early exit if we found both fields
            if (usernameField != null && passwordField != null) return
        }

        searchComponents(container)
        return Pair(usernameField, passwordField)
    }

    /**
     * Submit the dialog by finding and clicking appropriate buttons
     */
    private fun submitDialog(window: Window) {
        val submitButton = findSubmitButton(window)
        if (submitButton != null &&
            submitButton.isEnabled &&
            submitButton.isVisible
        ) {
            logger.info("Auto-submitting dialog using button: ${submitButton.text}")
            submitButton.doClick()
            return
        }

        logger.warn("Could not find suitable submit button for auto-submission")
    }

    private fun findSubmitButton(container: Container): JButton? {
        fun searchForButton(comp: Component): JButton? {
            return when (comp) {
                is JButton -> {
                    val text = comp.text?.lowercase() ?: ""
                    if (text.contains("ok") ||
                        text.contains("login") ||
                        text.contains("sign in")
                    ) {
                        comp
                    } else null
                }

                is Container -> {
                    comp.components?.firstNotNullOfOrNull { searchForButton(it) }
                }

                else -> null
            }
        }

        return searchForButton(container)
    }

    /**
     * Get window title safely
     */
    private fun getWindowTitle(window: Window): String? {
        return when (window) {
            is JDialog -> window.title
            is JFrame -> window.title
            else -> null
        }
    }

    /**
     * Cleanup method called when the service is disposed
     */
    override fun dispose() {
        try {
            if (isInitialized) {
                Toolkit.getDefaultToolkit().removeAWTEventListener(eventListener)
                isInitialized = false
                logger.info("NuGet dialog interceptor disposed")
            }
        } catch (e: Exception) {
            logger.error("Error disposing NuGet dialog interceptor", e)
        }
    }

    /**
     * Check if interceptor is running
     */
    fun isRunning(): Boolean = isInitialized

    /**
     * Manual trigger for testing
     */
    fun triggerManualCheck() {
        logger.info("Manual dialog check triggered")
        val windows = Window.getWindows()
        for (window in windows) {
            if (window.isVisible && isNuGetCredentialDialog(window)) {
                logger.info("Found NuGet dialog in manual check: ${window.javaClass.simpleName}")
                processNuGetDialog(window)
            }
        }
    }
}
