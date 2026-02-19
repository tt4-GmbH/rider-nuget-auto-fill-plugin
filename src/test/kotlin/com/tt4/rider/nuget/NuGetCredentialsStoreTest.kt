package com.tt4.rider.nuget

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class NuGetCredentialsStoreTest {

    // Instantiate directly — constructor takes no args and normalizeUrl/computeNewState
    // don't touch any IDE services.
    private val store = NuGetCredentialsStore()

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
}
