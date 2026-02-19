package com.tt4.rider.nuget.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class UrlValidationTest {

    // -------------------------------------------------------------------------
    // FeedCredentialsDialog.isValidUrl
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = [
        "https://api.nuget.org/v3/index.json",
        "http://internal.corp/nuget/v3/index.json",
        "https://pkgs.dev.azure.com/org/_packaging/feed/nuget/v3/index.json",
        "https://nuget.pkg.github.com/myorg/index.json",
        "http://localhost:8080/nuget/v3/index.json"
    ])
    fun `isValidUrl returns true for valid http and https URLs`(url: String) {
        assertTrue(FeedCredentialsDialog.isValidUrl(url))
    }

    @Test
    fun `isValidUrl returns false for empty string`() {
        assertFalse(FeedCredentialsDialog.isValidUrl(""))
    }

    @Test
    fun `isValidUrl returns false for URL without scheme`() {
        assertFalse(FeedCredentialsDialog.isValidUrl("api.nuget.org/v3/index.json"))
    }

    @Test
    fun `isValidUrl returns false for ftp scheme`() {
        assertFalse(FeedCredentialsDialog.isValidUrl("ftp://files.example.com/packages"))
    }

    @Test
    fun `isValidUrl returns false for plaintext non-URL`() {
        assertFalse(FeedCredentialsDialog.isValidUrl("not-a-url"))
    }

    @Test
    fun `isValidUrl returns false for javascript scheme`() {
        assertFalse(FeedCredentialsDialog.isValidUrl("javascript:alert(1)"))
    }

    @Test
    fun `isValidUrl returns false for whitespace only`() {
        assertFalse(FeedCredentialsDialog.isValidUrl("   "))
    }
}
