package com.tt4.rider.nuget

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NuGetDialogInterceptorTest {

    // -------------------------------------------------------------------------
    // matchesCredentialTitle
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = [
        "Enter Credentials",
        "ENTER CREDENTIALS",
        "enter credentials",
        "Authentication Required",
        "AUTHENTICATION REQUIRED",
        "Login Required",
        "Credentials for MyFeed",
        "Sign In",
        "SIGN IN",
        "Authenticate",
        "NuGet Authentication",
        "NuGet Credentials",
        "Package Source Credentials"
    ])
    fun `matchesCredentialTitle returns true for known credential dialog titles`(title: String) {
        assertTrue(NuGetDialogInterceptor.matchesCredentialTitle(title))
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "About",
        "Settings",
        "New Solution",
        "Run Configuration",
        "",
        "Git Push",
        "Commit Changes",
        "Project Properties"
    ])
    fun `matchesCredentialTitle returns false for unrelated dialog titles`(title: String) {
        assertFalse(NuGetDialogInterceptor.matchesCredentialTitle(title))
    }

    @Test
    fun `matchesCredentialTitle is case-insensitive for embedded patterns`() {
        assertTrue(NuGetDialogInterceptor.matchesCredentialTitle("Please SIGN IN to continue"))
        assertTrue(NuGetDialogInterceptor.matchesCredentialTitle("nuget AUTHENTICATION dialog"))
        assertTrue(NuGetDialogInterceptor.matchesCredentialTitle("PACKAGE SOURCE CREDENTIALS"))
    }

    // -------------------------------------------------------------------------
    // URL_PATTERN
    // -------------------------------------------------------------------------

    @Test
    fun `URL_PATTERN matches standard NuGet org index json URL`() {
        val text = "Please authenticate for https://api.nuget.org/v3/index.json"
        val match = NuGetDialogInterceptor.URL_PATTERN.find(text)
        assertNotNull(match)
        assertEquals("https://api.nuget.org/v3/index.json", match!!.value)
    }

    @Test
    fun `URL_PATTERN matches Azure DevOps feed URL`() {
        val text = "Feed: https://pkgs.dev.azure.com/myorg/_packaging/myfeed/nuget/v3/index.json "
        val match = NuGetDialogInterceptor.URL_PATTERN.find(text)
        assertNotNull(match)
        assertEquals("https://pkgs.dev.azure.com/myorg/_packaging/myfeed/nuget/v3/index.json", match!!.value)
    }

    @Test
    fun `URL_PATTERN matches GitHub Packages URL`() {
        val text = "<label>https://nuget.pkg.github.com/myorg/index.json</label>"
        val match = NuGetDialogInterceptor.URL_PATTERN.find(text)
        assertNotNull(match)
        assertEquals("https://nuget.pkg.github.com/myorg/index.json", match!!.value)
    }

    @Test
    fun `URL_PATTERN matches http scheme as well as https`() {
        val text = "http://internal.corp/nuget/v3/index.json"
        val match = NuGetDialogInterceptor.URL_PATTERN.find(text)
        assertNotNull(match)
        assertEquals("http://internal.corp/nuget/v3/index.json", match!!.value)
    }

    @Test
    fun `URL_PATTERN does not match URLs not ending in index json`() {
        val text = "https://api.nuget.org/v3/packages"
        assertNull(NuGetDialogInterceptor.URL_PATTERN.find(text))
    }

    @Test
    fun `URL_PATTERN does not match bare domain without path`() {
        val text = "https://api.nuget.org"
        assertNull(NuGetDialogInterceptor.URL_PATTERN.find(text))
    }

    @Test
    fun `URL_PATTERN returns first match when multiple URLs are present`() {
        val text = "URL1: https://api.nuget.org/v3/index.json URL2: https://pkgs.dev.azure.com/org/_packaging/feed/nuget/v3/index.json"
        val match = NuGetDialogInterceptor.URL_PATTERN.find(text)
        assertNotNull(match)
        assertEquals("https://api.nuget.org/v3/index.json", match!!.value)
    }

    @Test
    fun `URL_PATTERN stops match at space after URL`() {
        val text = "https://api.nuget.org/v3/index.json some other text"
        val match = NuGetDialogInterceptor.URL_PATTERN.find(text)
        assertNotNull(match)
        assertEquals("https://api.nuget.org/v3/index.json", match!!.value)
    }

    @Test
    fun `URL_PATTERN matches URL at end of string with no trailing character`() {
        val text = "https://api.nuget.org/v3/index.json"
        val match = NuGetDialogInterceptor.URL_PATTERN.find(text)
        assertNotNull(match)
        assertEquals("https://api.nuget.org/v3/index.json", match!!.value)
    }
}
