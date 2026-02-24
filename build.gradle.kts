import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import groovy.json.JsonSlurper
import java.net.URI

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "com.tt4"
version = "1.0.5"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

/**
 * Fetches the latest patch release version for each Rider major version since [sinceMajor],
 * plus the latest EAP build number, from the JetBrains releases API.
 * Falls back to an empty list on network errors (CI without internet, etc.).
 */
fun fetchRiderVersionsForVerification(sinceMajor: String): List<String> {
    return try {
        // Fetch all stable releases (no latest=true so we get all major versions)
        val stableUrl = URI("https://data.services.jetbrains.com/products/releases?code=RD&type=release").toURL()
        @Suppress("UNCHECKED_CAST")
        val stableReleases = (JsonSlurper().parseText(stableUrl.readText()) as Map<*, *>)["RD"] as List<Map<*, *>>

        // Group by major version, take the latest patch of each
        val stableVersions = stableReleases
            .mapNotNull { release ->
                val version = release["version"] as? String ?: return@mapNotNull null
                val majorVersion = release["majorVersion"] as? String ?: return@mapNotNull null
                majorVersion to version
            }
            .groupBy({ it.first }, { it.second })
            .filter { (major, _) -> major >= sinceMajor }
            .map { (_, versions) -> versions.maxOrNull()!! }
            .sorted()

        // Fetch the latest EAP build number (Maven uses build number, not version string).
        // Only include it if it's actually available in the Maven repository.
        val eapUrl = URI("https://data.services.jetbrains.com/products/releases?code=RD&type=eap&latest=true").toURL()
        @Suppress("UNCHECKED_CAST")
        val eapReleases = (JsonSlurper().parseText(eapUrl.readText()) as Map<*, *>)["RD"] as List<Map<*, *>>
        val latestEapBuild = eapReleases.firstOrNull()?.get("build") as? String
        val eapAvailable = latestEapBuild != null && try {
            val pomUrl = URI("https://cache-redirector.jetbrains.com/www.jetbrains.com/intellij-repository/snapshots/com/jetbrains/intellij/rider/riderRD/$latestEapBuild/riderRD-$latestEapBuild.pom").toURL()
            val connection = pomUrl.openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.requestMethod = "HEAD"
            connection.connect()
            val status = connection.responseCode
            connection.disconnect()
            status == 200
        } catch (_: Exception) { false }

        val versions = if (eapAvailable) stableVersions + latestEapBuild!! else stableVersions

        println("[pluginVerification] Resolved Rider versions from JetBrains API: $versions")
        versions
    } catch (e: Exception) {
        println("[pluginVerification] WARNING: Could not fetch Rider versions from JetBrains API: ${e.message}")
        println("[pluginVerification] Falling back to empty list — skipping version-specific verification.")
        emptyList()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        rider("2024.3") {
            useInstaller = false
        }
        //testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation here, example:
        // bundledPlugin("com.intellij.java")
    }

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
    testImplementation("io.mockk:mockk:1.14.5")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        // select {} is not supported for Rider (useInstaller always defaults to true, which Rider doesn't support).
        // See: https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/1852
        // Instead we query the JetBrains releases API dynamically at build time.
        ides {
            fetchRiderVersionsForVerification(sinceMajor = "2024.1").forEach { version ->
                create(IntelliJPlatformType.Rider, version)
            }
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
