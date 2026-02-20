package com.tt4.rider.nuget

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.JDOMUtil
import com.intellij.util.xmlb.XmlSerializer
import java.io.File

/**
 * Data class for NuGet credentials
 */
data class NuGetCredentials(
    val username: String,
    val password: String,
    val feedUrl: String,
    val autoSubmit: Boolean = false
)

/**
 * Service for managing NuGet feed credentials securely using IntelliJ's PasswordSafe
 */
@Service
@State(name = "NuGetCredentialStore", storages = [Storage("nuget-credentials.xml")])
class NuGetCredentialsStore : PersistentStateComponent<NuGetCredentialsStore.State> {

    companion object {
        fun getInstance(): NuGetCredentialsStore {
            return ApplicationManager.getApplication().getService(NuGetCredentialsStore::class.java)
        }

        private const val CREDENTIAL_SERVICE_NAME = "NuGetAutoFill"
        private val logger = thisLogger()

        private val backupFile: File
            get() {
                val configPath = PathManager.getConfigPath()
                return File(configPath, "nuget-credentials-backup.xml")
            }

        /**
         * Pure function: determines which state to apply on loadState().
         * Returns null if the incoming state should be ignored (empty incoming
         * while current already has feeds — crash/corruption guard).
         */
        internal fun computeNewState(incoming: State, current: State): State? {
            if (incoming.feedConfigurations.isEmpty() && current.feedConfigurations.isNotEmpty()) return null
            return incoming
        }
    }

    /**
     * State class for persisting non-sensitive data
     */
    data class State(
        var feedConfigurations: MutableMap<String, FeedConfiguration> = mutableMapOf()
    )

    /**
     * Configuration for a NuGet feed (non-sensitive data only)
     */
    data class FeedConfiguration(
        var feedUrl: String = "",
        var username: String = "",
        var autoSubmit: Boolean = false,
        var enabled: Boolean = true
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        val newState = computeNewState(state, this.state)

        if (newState == null) {
            logger.warn("Ignoring empty state load — keeping ${this.state.feedConfigurations.size} existing feed(s)")
            return
        }

        // If the loaded state is empty, try to restore from backup
        if (newState.feedConfigurations.isEmpty()) {
            val restored = tryRestoreFromBackup()
            if (restored != null) {
                logger.warn("Main state was empty — restored ${restored.feedConfigurations.size} feed(s) from backup")
                this.state = restored
                return
            }
        }

        this.state = newState
    }

    private fun tryRestoreFromBackup(): State? {
        return try {
            val backup = backupFile
            if (!backup.exists()) return null
            val element = JDOMUtil.load(backup)
            XmlSerializer.deserialize(element, State::class.java)
        } catch (e: Exception) {
            logger.warn("Failed to restore from backup", e)
            null
        }
    }

    private fun saveBackup() {
        try {
            if (state.feedConfigurations.isEmpty()) return
            val element = XmlSerializer.serialize(state)
            JDOMUtil.write(element, backupFile)
        } catch (e: Exception) {
            logger.warn("Failed to save credential backup", e)
        }
    }

    /**
     * Store credentials for a NuGet feed
     */
    fun storeCredentials(credentials: NuGetCredentials) {
        val normalizedUrl = normalizeUrl(credentials.feedUrl)

        logger.info("Storing credentials for feed: $normalizedUrl")

        // Store non-sensitive configuration
        state.feedConfigurations[normalizedUrl] = FeedConfiguration(
            feedUrl = normalizedUrl,
            username = credentials.username,
            autoSubmit = credentials.autoSubmit,
            enabled = true
        )

        // Store sensitive data in secure storage
        val credentialAttributes = CredentialAttributes(
            serviceName = CREDENTIAL_SERVICE_NAME,
            userName = normalizedUrl
        )

        val secureCredentials = Credentials(credentials.username, credentials.password)
        PasswordSafe.instance.set(credentialAttributes, secureCredentials)

        // Verify the write succeeded by reading back
        val verification = PasswordSafe.instance.get(credentialAttributes)
        if (verification == null) {
            logger.error("PasswordSafe write verification failed for: $normalizedUrl — password was not stored in keychain")
            throw IllegalStateException("Failed to store password in system keychain for: $normalizedUrl")
        }

        logger.info("Successfully stored credentials for: $normalizedUrl")
        saveBackup()
    }

    /**
     * Retrieve credentials for a NuGet feed
     */
    fun getCredentials(feedUrl: String?): NuGetCredentials? {
        if (feedUrl.isNullOrBlank()) {
            logger.debug("Feed URL is null or blank")
            return null
        }

        val normalizedUrl = normalizeUrl(feedUrl)
        val config = state.feedConfigurations[normalizedUrl]

        if (config == null) {
            logger.debug("No configuration found for feed: $normalizedUrl")
            return null
        }

        if (!config.enabled) {
            logger.debug("Feed is disabled: $normalizedUrl")
            return null
        }

        // Retrieve from secure storage
        val credentialAttributes = CredentialAttributes(
            serviceName = CREDENTIAL_SERVICE_NAME,
            userName = normalizedUrl
        )

        val secureCredentials = PasswordSafe.instance.get(credentialAttributes)
        if (secureCredentials == null) {
            logger.warn("No secure credentials found for: $normalizedUrl")
            return null
        }

        return NuGetCredentials(
            username = config.username,
            password = secureCredentials.getPasswordAsString() ?: "",
            feedUrl = normalizedUrl,
            autoSubmit = config.autoSubmit
        )
    }

    /**
     * Remove credentials for a NuGet feed
     */
    fun removeCredentials(feedUrl: String) {
        val normalizedUrl = normalizeUrl(feedUrl)

        logger.info("Removing credentials for feed: $normalizedUrl")

        // Remove from configuration
        state.feedConfigurations.remove(normalizedUrl)

        // Remove from secure storage
        val credentialAttributes = CredentialAttributes(
            serviceName = CREDENTIAL_SERVICE_NAME,
            userName = normalizedUrl
        )
        PasswordSafe.instance.set(credentialAttributes, null)
        saveBackup()
    }

    /**
     * Get all configured feeds
     */
    fun getAllFeeds(): List<FeedConfiguration> {
        return state.feedConfigurations.values.toList()
    }

    /**
     * Enable/disable autofill for a specific feed
     */
    fun setFeedEnabled(feedUrl: String, enabled: Boolean) {
        val normalizedUrl = normalizeUrl(feedUrl)
        state.feedConfigurations[normalizedUrl]?.enabled = enabled
        logger.info("Set feed enabled=$enabled for: $normalizedUrl")
    }

    /**
     * Update auto-submit setting for a feed
     */
    fun setAutoSubmit(feedUrl: String, autoSubmit: Boolean) {
        val normalizedUrl = normalizeUrl(feedUrl)
        state.feedConfigurations[normalizedUrl]?.autoSubmit = autoSubmit
        logger.info("Set auto-submit=$autoSubmit for: $normalizedUrl")
    }

    /**
     * Check if autofill is enabled for a feed
     */
    fun isFeedEnabled(feedUrl: String?): Boolean {
        if (feedUrl.isNullOrBlank()) return false
        val normalizedUrl = normalizeUrl(feedUrl)
        return state.feedConfigurations[normalizedUrl]?.enabled ?: false
    }

    /**
     * Normalize URL for consistent storage and lookup
     */
    internal fun normalizeUrl(url: String): String {
        return url.trim()
            .lowercase()
            .removeSuffix("/")
            .let { if (!it.startsWith("http")) "https://$it" else it }
    }

    /**
     * Test credentials against a feed (placeholder for future implementation)
     */
    fun testCredentials(credentials: NuGetCredentials): Boolean {
        logger.info("Testing credentials for: ${credentials.feedUrl}")
        // TODO: Implement actual HTTP test
        return true
    }

    /**
     * Clear all stored credentials (for reset/cleanup)
     */
    fun clearAllCredentials() {
        logger.info("Clearing all stored credentials")
        val feedUrls = state.feedConfigurations.keys.toList()
        feedUrls.forEach { removeCredentials(it) }
    }

    /**
     * Check if any credentials are stored
     */
    fun hasStoredCredentials(): Boolean {
        return state.feedConfigurations.isNotEmpty()
    }

    /**
     * Get credential summary for logging (without passwords)
     */
    fun getCredentialSummary(): String {
        val feeds = state.feedConfigurations.values
        val enabledCount = feeds.count { it.enabled }
        val totalCount = feeds.size
        return "Stored credentials: $enabledCount enabled / $totalCount total"
    }
}
