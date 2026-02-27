package com.tt4.rider.nuget

import com.intellij.ide.passwordSafe.PasswordSafe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class NuGetCredentialsStoreTest {

    // Instantiate directly — constructor takes no args and normalizeUrl/computeNewState
    // don't touch any IDE services.
    private val store = NuGetCredentialsStore()

    @AfterEach
    fun resetSeams() {
        NuGetCredentialsStore.passwordSafeProvider = { PasswordSafe.instance }
        NuGetCredentialsStore.storeRetryDelayMs = 150L
    }

    // -------------------------------------------------------------------------
    // normalizeUrl
    // -------------------------------------------------------------------------

    @Test
    fun `normalizeUrl trims leading and trailing whitespace`() {
        assertEquals(
            "https://api.nuget.org/v3/index.json",
            store.normalizeUrl("  https://api.nuget.org/v3/index.json  ")
        )
    }

    @Test
    fun `normalizeUrl lowercases the entire URL`() {
        assertEquals(
            "https://api.nuget.org/v3/index.json",
            store.normalizeUrl("https://API.NUGET.ORG/V3/Index.Json")
        )
    }

    @Test
    fun `normalizeUrl removes a single trailing slash`() {
        assertEquals(
            "https://api.nuget.org/v3/index.json",
            store.normalizeUrl("https://api.nuget.org/v3/index.json/")
        )
    }

    @Test
    fun `normalizeUrl prepends https when scheme is missing`() {
        assertEquals(
            "https://pkgs.dev.azure.com/myorg/_packaging/feed/nuget/v3/index.json",
            store.normalizeUrl("pkgs.dev.azure.com/myorg/_packaging/feed/nuget/v3/index.json")
        )
    }

    @Test
    fun `normalizeUrl does not prepend https when https scheme already present`() {
        assertEquals(
            "https://nuget.pkg.github.com/myorg/index.json",
            store.normalizeUrl("https://nuget.pkg.github.com/myorg/index.json")
        )
    }

    @Test
    fun `normalizeUrl does not prepend https when http scheme already present`() {
        assertEquals(
            "http://internal.mycompany.com/nuget/index.json",
            store.normalizeUrl("http://internal.mycompany.com/nuget/index.json")
        )
    }

    @ParameterizedTest
    @CsvSource(
        "https://API.NUGET.ORG/V3/Index.Json/,  https://api.nuget.org/v3/index.json",
        "  PKGS.DEV.AZURE.COM/org/_packaging/feed/nuget/v3/index.json, https://pkgs.dev.azure.com/org/_packaging/feed/nuget/v3/index.json",
        "https://nuget.pkg.github.com/owner/index.json  ,  https://nuget.pkg.github.com/owner/index.json"
    )
    fun `normalizeUrl handles combined transformations`(input: String, expected: String) {
        assertEquals(expected.trim(), store.normalizeUrl(input.trim()))
    }

    // -------------------------------------------------------------------------
    // computeNewState
    // -------------------------------------------------------------------------

    @Test
    fun `computeNewState returns null when incoming is empty and current has feeds`() {
        val current = NuGetCredentialsStore.State(
            feedConfigurations = mutableMapOf(
                "https://api.nuget.org/v3/index.json" to NuGetCredentialsStore.FeedConfiguration(
                    feedUrl = "https://api.nuget.org/v3/index.json"
                )
            )
        )
        val incoming = NuGetCredentialsStore.State() // empty — crash/corruption scenario
        assertNull(NuGetCredentialsStore.computeNewState(incoming, current))
    }

    @Test
    fun `computeNewState returns incoming when both states are empty`() {
        val current = NuGetCredentialsStore.State()
        val incoming = NuGetCredentialsStore.State()
        assertNotNull(NuGetCredentialsStore.computeNewState(incoming, current))
    }

    @Test
    fun `computeNewState returns incoming when it has data`() {
        val current = NuGetCredentialsStore.State(
            feedConfigurations = mutableMapOf(
                "https://api.nuget.org/v3/index.json" to NuGetCredentialsStore.FeedConfiguration()
            )
        )
        val incoming = NuGetCredentialsStore.State(
            feedConfigurations = mutableMapOf(
                "https://pkgs.dev.azure.com/org/_packaging/feed/nuget/v3/index.json" to
                    NuGetCredentialsStore.FeedConfiguration()
            )
        )
        assertEquals(incoming, NuGetCredentialsStore.computeNewState(incoming, current))
    }

    @Test
    fun `computeNewState returns incoming when current is empty and incoming has data`() {
        val current = NuGetCredentialsStore.State()
        val incoming = NuGetCredentialsStore.State(
            feedConfigurations = mutableMapOf(
                "https://api.nuget.org/v3/index.json" to NuGetCredentialsStore.FeedConfiguration()
            )
        )
        assertEquals(incoming, NuGetCredentialsStore.computeNewState(incoming, current))
    }

    // -------------------------------------------------------------------------
    // resolveToStoredUrl
    // -------------------------------------------------------------------------

    private fun storeWithIndexJson(vararg urls: String) {
        store.loadState(NuGetCredentialsStore.State(
            feedConfigurations = urls.associate { url ->
                url to NuGetCredentialsStore.FeedConfiguration(feedUrl = url, username = "user", enabled = true)
            }.toMutableMap()
        ))
    }

    @Test
    fun `resolveToStoredUrl returns exact match when URL already includes index json`() {
        storeWithIndexJson("https://myserver.com/feed/index.json")
        assertEquals(
            "https://myserver.com/feed/index.json",
            store.resolveToStoredUrl("https://myserver.com/feed/index.json")
        )
    }

    @Test
    fun `resolveToStoredUrl resolves base URL with trailing slash to stored index json URL`() {
        storeWithIndexJson("https://myserver.com/feed/index.json")
        assertEquals(
            "https://myserver.com/feed/index.json",
            store.resolveToStoredUrl("https://myserver.com/feed/")
        )
    }

    @Test
    fun `resolveToStoredUrl resolves base URL without trailing slash to stored index json URL`() {
        storeWithIndexJson("https://myserver.com/feed/index.json")
        assertEquals(
            "https://myserver.com/feed/index.json",
            store.resolveToStoredUrl("https://myserver.com/feed")
        )
    }

    @Test
    fun `resolveToStoredUrl does not match a different feed that shares a URL prefix`() {
        storeWithIndexJson("https://myserver.com/nuget-local-snapshots/index.json")
        // "nuget-local-snapshots/index.json" must NOT match the lookup for "nuget-local"
        assertNull(store.resolveToStoredUrl("https://myserver.com/nuget-local"))
    }

    @Test
    fun `resolveToStoredUrl resolves Artifactory-style base URL to stored index json URL`() {
        storeWithIndexJson("https://my-company.jfrog.io/artifactory/api/nuget/v3/nuget-local/index.json")
        assertEquals(
            "https://my-company.jfrog.io/artifactory/api/nuget/v3/nuget-local/index.json",
            store.resolveToStoredUrl("https://my-company.jfrog.io/artifactory/api/nuget/v3/nuget-local/")
        )
    }

    @Test
    fun `resolveToStoredUrl returns null when no stored feed matches`() {
        storeWithIndexJson("https://myserver.com/feed/index.json")
        assertNull(store.resolveToStoredUrl("https://other.server.com/feed"))
    }

    @Test
    fun `resolveToStoredUrl returns null for null input`() {
        storeWithIndexJson("https://myserver.com/feed/index.json")
        assertNull(store.resolveToStoredUrl(null))
    }

    @Test
    fun `resolveToStoredUrl returns null for blank input`() {
        storeWithIndexJson("https://myserver.com/feed/index.json")
        assertNull(store.resolveToStoredUrl("   "))
    }

    @Test
    fun `resolveToStoredUrl returns null when store is empty`() {
        assertNull(store.resolveToStoredUrl("https://myserver.com/feed/index.json"))
    }

    // -------------------------------------------------------------------------
    // storeCredentials atomicity + retry
    // -------------------------------------------------------------------------

    private fun mockPasswordSafe(
        setAnswer: () -> Unit = {},
        getAnswer: () -> com.intellij.credentialStore.Credentials? = { mockk() }
    ): PasswordSafe {
        NuGetCredentialsStore.storeRetryDelayMs = 0L
        return mockk<PasswordSafe>().also { ps ->
            every { ps.set(any(), any()) } answers { setAnswer() }
            every { ps.get(any()) } answers { getAnswer() }
            NuGetCredentialsStore.passwordSafeProvider = { ps }
        }
    }

    @Test
    fun `storeCredentials adds feed to state when PasswordSafe succeeds on first attempt`() {
        mockPasswordSafe()

        store.storeCredentials(NuGetCredentials("user", "secret", "https://api.nuget.org/v3/index.json"))

        assertEquals(1, store.getAllFeeds().size)
        assertEquals("https://api.nuget.org/v3/index.json", store.getAllFeeds().first().feedUrl)
        assertEquals("user", store.getAllFeeds().first().username)
    }

    @Test
    fun `storeCredentials retries and succeeds when set() throws on first attempt`() {
        var attempt = 0
        mockPasswordSafe(setAnswer = {
            attempt++
            if (attempt == 1) throw RuntimeException("transient keychain error")
        })

        store.storeCredentials(NuGetCredentials("user", "secret", "https://api.nuget.org/v3/index.json"))

        assertEquals(1, store.getAllFeeds().size)
    }

    @Test
    fun `storeCredentials retries and succeeds when get() verification returns null on first attempt`() {
        var getAttempt = 0
        mockPasswordSafe(getAnswer = {
            getAttempt++
            if (getAttempt == 1) null else mockk()
        })

        store.storeCredentials(NuGetCredentials("user", "secret", "https://api.nuget.org/v3/index.json"))

        assertEquals(1, store.getAllFeeds().size)
    }

    @Test
    fun `storeCredentials rolls back feedConfigurations when set() always throws`() {
        mockPasswordSafe(setAnswer = { throw RuntimeException("keychain unavailable") })

        // IntelliJ's TestLoggerFactory promotes logger.error() to AssertionError during tests,
        // so we accept any Throwable. The important assertion is the state check below.
        try {
            store.storeCredentials(NuGetCredentials("user", "secret", "https://api.nuget.org/v3/index.json"))
            fail("Expected storeCredentials to throw")
        } catch (_: Throwable) { }

        assertTrue(store.getAllFeeds().isEmpty(), "Feed must not remain in state after a keychain failure")
    }

    @Test
    fun `two feeds with the same username are both stored in XML state independently`() {
        mockPasswordSafe()

        store.storeCredentials(NuGetCredentials("alice", "pass1", "https://feed1.example.com/v3/index.json"))
        store.storeCredentials(NuGetCredentials("alice", "pass2", "https://feed2.example.com/v3/index.json"))

        assertEquals(2, store.getAllFeeds().size)
        val urls = store.getAllFeeds().map { it.feedUrl }.toSet()
        assertTrue("https://feed1.example.com/v3/index.json" in urls)
        assertTrue("https://feed2.example.com/v3/index.json" in urls)
    }

    @Test
    fun `getCredentials migrates from legacy key format transparently on first access`() {
        NuGetCredentialsStore.storeRetryDelayMs = 0L
        val mockPs = mockk<PasswordSafe>()
        val legacyCreds = mockk<com.intellij.credentialStore.Credentials>()
        every { legacyCreds.getPasswordAsString() } returns "migrated-password"

        // New-format key (>= 1.0.5) has no entry
        every { mockPs.get(match { it.serviceName.startsWith("NuGetAutoFill:") }) } returns null
        // Legacy key (< 1.0.5) has the credential
        every { mockPs.get(match { it.serviceName == "NuGetAutoFill" }) } returns legacyCreds
        // Migration writes (store new, delete old)
        every { mockPs.set(any(), any()) } just Runs

        NuGetCredentialsStore.passwordSafeProvider = { mockPs }

        // Seed XML state as it would exist after a pre-1.0.5 install
        store.loadState(NuGetCredentialsStore.State(
            feedConfigurations = mutableMapOf(
                "https://api.nuget.org/v3/index.json" to NuGetCredentialsStore.FeedConfiguration(
                    feedUrl = "https://api.nuget.org/v3/index.json",
                    username = "user",
                    enabled = true
                )
            )
        ))

        val result = store.getCredentials("https://api.nuget.org/v3/index.json")

        assertNotNull(result, "Legacy credential should be returned after migration")
    }

    @Test
    fun `storeCredentials rolls back feedConfigurations when get() verification always returns null`() {
        mockPasswordSafe(getAnswer = { null })

        try {
            store.storeCredentials(NuGetCredentials("user", "secret", "https://api.nuget.org/v3/index.json"))
            fail("Expected storeCredentials to throw")
        } catch (_: Throwable) { }

        assertTrue(store.getAllFeeds().isEmpty(), "Feed must not remain in state after verification always failing")
    }
}
