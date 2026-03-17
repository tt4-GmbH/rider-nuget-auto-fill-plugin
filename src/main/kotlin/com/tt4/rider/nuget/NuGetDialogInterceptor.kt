package com.tt4.rider.nuget

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import java.awt.*
import java.awt.event.AWTEventListener
import java.awt.event.WindowEvent
import java.util.Collections
import java.util.WeakHashMap
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

        /**
         * Broader URL pattern used as a fallback in extractFeedInformation() when
         * the dialog shows a base feed URL without the /index.json suffix
         * (e.g. package-install dialogs in Rider's NuGet Package Manager).
         * Not used for content-based dialog detection — only for URL extraction.
         */
        internal val BASE_URL_PATTERN = Regex("""https?://[^\s<>"]+""")

        /**
         * Recursively searches text components for a URL matching [pattern].
         * Checks JLabel, JTextArea, JEditorPane, and read-only JTextField (skipping editable
         * fields to avoid reading the username input). JPasswordField extends JTextField and
         * is editable, so it is always skipped.
         * Defaults to URL_PATTERN (index.json URLs).
         * Internal for unit testing.
         */
        internal fun findUrlInLabels(container: Container, pattern: Regex = URL_PATTERN): String? {
            for (component in container.components) {
                val text: String? = when (component) {
                    is JLabel      -> component.text
                    is JTextArea   -> component.text
                    is JEditorPane -> component.text
                    is JTextField  -> if (!component.isEditable) component.text else null
                    else           -> null
                }
                if (text != null) {
                    val match = pattern.find(text)
                    if (match != null) return match.value
                }
                if (component is Container) {
                    val found = findUrlInLabels(component, pattern)
                    if (found != null) return found
                }
            }
            return null
        }

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
     * Tracks windows that have already been processed (filled or decided no credentials apply).
     * WeakHashMap ensures windows are garbage-collected normally when closed.
     * Reset on WINDOW_OPENED/WINDOW_CLOSED so reused windows are treated as fresh.
     */
    private val processedWindows: MutableSet<Window> =
        Collections.newSetFromMap(WeakHashMap())

    /**
     * Initialize the dialog interception
     */
    fun initialize() {
        logger.info("[NuGetAutoFill] initialize() called — isInitialized=$isInitialized")
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

            // Catch any credential dialog that appeared before the listener was registered
            // (e.g. NuGet auto-restore fires during Rider startup before applicationActivated)
            triggerManualCheck()

        } catch (e: Exception) {
            logger.error("Failed to initialize NuGet dialog interceptor", e)
        }
    }

    /**
     * AWT Event listener for dialog detection.
     *
     * Handles three event types:
     * - WINDOW_OPENED: primary trigger; clears the dedup set for this window so a
     *   reused instance is always treated as fresh.
     * - WINDOW_ACTIVATED: catches windows that were already created and are made visible
     *   again via setVisible(true) — WINDOW_OPENED does NOT fire for those. Guarded by
     *   processedWindows to avoid re-processing on every focus event.
     * - WINDOW_CLOSED / WINDOW_CLOSING: evict from processedWindows so a reused window
     *   (setVisible → close → setVisible again) is detected on the next show.
     */
    private inner class NuGetDialogEventListener : AWTEventListener {

        override fun eventDispatched(event: AWTEvent) {
            if (event !is WindowEvent) return
            val window = event.window
            // Dialog detection (title/content checks) is pure Swing reads — safe on EDT.
            // processNuGetDialog() offloads PasswordSafe I/O to a background thread internally.
            try {
                when (event.id) {
                    WindowEvent.WINDOW_OPENED -> {
                        // Fresh window: clear any stale dedup entry and process immediately
                        processedWindows.remove(window)
                        checkAndProcess(window)
                    }
                    WindowEvent.WINDOW_ACTIVATED -> {
                        // Secondary trigger for reused windows; skip if already handled
                        if (window !in processedWindows) checkAndProcess(window)
                    }
                    WindowEvent.WINDOW_CLOSED,
                    WindowEvent.WINDOW_CLOSING -> {
                        // Allow the window to be detected again if it is reused later
                        processedWindows.remove(window)
                    }
                }
            } catch (e: Exception) {
                logger.error("Error processing potential NuGet dialog", e)
            }
        }
    }

    /**
     * Check a window and process it if it looks like a NuGet credential dialog.
     * Marks the window as processed regardless of outcome to prevent repeated attempts.
     */
    private fun checkAndProcess(window: Window) {
        if (isNuGetCredentialDialog(window)) {
            logger.info("Detected NuGet credential dialog: ${window.javaClass.simpleName}")
            processedWindows.add(window)
            processNuGetDialog(window)
        } else {
            // Mark non-matching windows too, so WINDOW_ACTIVATED doesn't keep re-evaluating them
            processedWindows.add(window)
        }
    }

    /**
     * Enhanced dialog detection with multiple strategies.
     * Always logs the window title at DEBUG level so missed dialogs can be diagnosed via the IDE log.
     */
    private fun isNuGetCredentialDialog(window: Window): Boolean {
        val title = getWindowTitle(window)
        logger.info("[NuGetAutoFill] Window event: title='$title', class='${window.javaClass.simpleName}'")
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
        // extractFeedInformation() only reads Swing component text — safe on EDT
        val feedInfo = extractFeedInformation(window)
        logger.debug("Extracted feed info: url=${feedInfo.url}, name=${feedInfo.name}")

        // PasswordSafe.get() is blocking I/O — must not be called on EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Resolve the dialog URL to a stored feed URL.
                // Handles dialogs that show the base URL without /index.json
                // (e.g. "https://myserver.com/feed/" → "https://myserver.com/feed/index.json").
                // When no URL is visible in the dialog at all, fall back to the single enabled
                // feed (if exactly one is configured).
                val resolvedUrl: String? = if (feedInfo.url != null) {
                    credentialStore.resolveToStoredUrl(feedInfo.url).also { resolved ->
                        if (resolved == null)
                            logger.info("[NuGetAutoFill] Dialog URL '${feedInfo.url}' did not match any stored feed")
                    }
                } else {
                    val enabled = credentialStore.getAllFeeds().filter { it.enabled }
                    when (enabled.size) {
                        1 -> {
                            logger.info("[NuGetAutoFill] No URL in credential dialog — using single enabled feed: ${enabled[0].feedUrl}")
                            enabled[0].feedUrl
                        }
                        else -> {
                            logger.info("[NuGetAutoFill] No URL in credential dialog and ${enabled.size} enabled feeds — cannot determine which feed to use")
                            null
                        }
                    }
                }
                val credentials = credentialStore.getCredentials(resolvedUrl)
                // fillCredentials() manipulates Swing components — must run on EDT
                // ModalityState.any() ensures this runs even while the credential dialog is open
                ApplicationManager.getApplication().invokeLater({
                    if (!window.isVisible) {
                        // Dialog was dismissed while credentials were being retrieved.
                        // Remove from processedWindows so WINDOW_ACTIVATED can retry if the
                        // same window instance is reused (Rider's setVisible pattern).
                        processedWindows.remove(window)
                        return@invokeLater
                    }
                    if (credentials != null && credentialStore.isFeedEnabled(resolvedUrl)) {
                        logger.info("Auto-filling credentials for feed: $resolvedUrl")
                        fillCredentials(window, credentials)
                        // Window stays in processedWindows — filled successfully, no retry needed
                    } else {
                        logger.debug("No credentials found or feed disabled for: ${feedInfo.url}")
                        // Remove from processedWindows so WINDOW_ACTIVATED can retry.
                        // Rider reuses the same dialog window instance across restore attempts
                        // (setVisible(false/true) rather than dispose+new), so WINDOW_OPENED
                        // won't fire again. Without this, a failed fill permanently blocks retry.
                        processedWindows.remove(window)
                    }
                }, ModalityState.any())
            } catch (e: Exception) {
                logger.error("Error retrieving credentials for NuGet dialog", e)
                // Must remove on EDT — processedWindows (WeakHashMap) is not thread-safe
                ApplicationManager.getApplication().invokeLater({
                    processedWindows.remove(window)
                }, ModalityState.any())
            }
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
     * Extract feed information from dialog.
     * Tries the strict URL_PATTERN (requires /index.json) first.
     * Falls back to BASE_URL_PATTERN to handle dialogs that show only the
     * base feed URL without the /index.json suffix (e.g. package-install dialogs).
     */
    private fun extractFeedInformation(window: Window): FeedInfo {
        val url = findUrlInLabels(window, URL_PATTERN)
            ?: findUrlInLabels(window, BASE_URL_PATTERN)
        return FeedInfo(url, null)
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
                    // Skip non-editable fields (e.g. read-only display fields showing the feed URL)
                    if (usernameField == null && comp.isEditable) usernameField = comp
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
     * Submit the dialog by clicking its default button or the first matching submit button.
     *
     * Tries [JDialog.rootPane.defaultButton] first — this is set by the dialog author and
     * is exactly the button that fires on Enter. Falls back to text-based button search
     * for dialogs that don't set a default button.
     */
    private fun submitDialog(window: Window) {
        // Most reliable: the dialog's declared default button (what Enter key would press)
        val defaultButton = (window as? JDialog)?.rootPane?.defaultButton
        if (defaultButton != null && defaultButton.isEnabled && defaultButton.isVisible) {
            logger.info("Auto-submitting dialog using default button: ${defaultButton.text}")
            defaultButton.doClick()
            return
        }

        val submitButton = findSubmitButton(window)
        if (submitButton != null && submitButton.isEnabled && submitButton.isVisible) {
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
                    // comp.text is lowercased before comparison — all checks are case-insensitive
                    val text = comp.text?.lowercase() ?: ""
                    if (text.contains("ok") ||
                        text.contains("login") ||
                        text.contains("log in") ||
                        text.contains("sign in") ||
                        text.contains("submit") ||
                        text.contains("authenticate") ||
                        text.contains("connect")
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
            if (window.isVisible && window !in processedWindows && isNuGetCredentialDialog(window)) {
                logger.info("Found NuGet dialog in manual check: ${window.javaClass.simpleName}")
                processedWindows.add(window)
                processNuGetDialog(window)
            }
        }
    }
}
