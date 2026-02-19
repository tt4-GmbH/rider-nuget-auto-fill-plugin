package com.tt4.rider.nuget.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PresetUrlGenerationTest {

    // -------------------------------------------------------------------------
    // NUGET_ORG_URL
    // -------------------------------------------------------------------------

    @Test
    fun `NUGET_ORG_URL is the canonical NuGet org index json URL`() {
        assertEquals("https://api.nuget.org/v3/index.json", FeedCredentialsDialog.NUGET_ORG_URL)
    }

    // -------------------------------------------------------------------------
    // buildAzureDevOpsUrl
    // -------------------------------------------------------------------------

    @Test
    fun `buildAzureDevOpsUrl produces correct URL for org and feed`() {
        assertEquals(
            "https://pkgs.dev.azure.com/myorg/_packaging/myfeed/nuget/v3/index.json",
            FeedCredentialsDialog.buildAzureDevOpsUrl("myorg", "myfeed")
        )
    }

    @Test
    fun `buildAzureDevOpsUrl handles org and feed names with hyphens`() {
        assertEquals(
            "https://pkgs.dev.azure.com/my-company/_packaging/shared-feed/nuget/v3/index.json",
            FeedCredentialsDialog.buildAzureDevOpsUrl("my-company", "shared-feed")
        )
    }

    @Test
    fun `buildAzureDevOpsUrl preserves casing of org and feed`() {
        assertEquals(
            "https://pkgs.dev.azure.com/MyOrg/_packaging/MyFeed/nuget/v3/index.json",
            FeedCredentialsDialog.buildAzureDevOpsUrl("MyOrg", "MyFeed")
        )
    }

    // -------------------------------------------------------------------------
    // buildGitHubPackagesUrl
    // -------------------------------------------------------------------------

    @Test
    fun `buildGitHubPackagesUrl produces correct URL for organization`() {
        assertEquals(
            "https://nuget.pkg.github.com/myorg/index.json",
            FeedCredentialsDialog.buildGitHubPackagesUrl("myorg")
        )
    }

    @Test
    fun `buildGitHubPackagesUrl produces correct URL for individual user`() {
        assertEquals(
            "https://nuget.pkg.github.com/janedoe/index.json",
            FeedCredentialsDialog.buildGitHubPackagesUrl("janedoe")
        )
    }

    @Test
    fun `buildGitHubPackagesUrl preserves casing of owner`() {
        assertEquals(
            "https://nuget.pkg.github.com/MyOrg/index.json",
            FeedCredentialsDialog.buildGitHubPackagesUrl("MyOrg")
        )
    }
}
