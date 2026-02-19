@file:Suppress("DialogTitleCapitalization")

package com.tt4.rider.nuget.ui

import com.tt4.rider.nuget.NuGetCredentials
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.text
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Dialog for adding/editing individual feed credentials
 */
class FeedCredentialsDialog(
    parent: DialogWrapper,
    private val existingFeedUrl: String?,
    private val existingCredentials: NuGetCredentials?
) : DialogWrapper(parent.contentPanel, true) {

    companion object {
        internal const val NUGET_ORG_URL = "https://api.nuget.org/v3/index.json"

        internal fun buildAzureDevOpsUrl(org: String, feed: String): String =
            "https://pkgs.dev.azure.com/$org/_packaging/$feed/nuget/v3/index.json"

        internal fun buildGitHubPackagesUrl(owner: String): String =
            "https://nuget.pkg.github.com/$owner/index.json"

        internal fun isValidUrl(url: String): Boolean {
            return try {
                val uri = java.net.URI(url)
                val urlObj = uri.toURL()
                urlObj.protocol in listOf("http", "https")
            } catch (_: Exception) {
                false
            }
        }
    }

    private lateinit var feedUrlField: JBTextField
    private lateinit var usernameField: JBTextField
    private lateinit var passwordField: JBPasswordField
    private lateinit var autoSubmitCheckbox: JBCheckBox
    private var result: NuGetCredentials? = null

    init {
        title = if (existingCredentials == null) "Add NuGet Feed" else "Edit NuGet Feed"
        init()
        setupFocusValidationClearing()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Feed URL:") {
                feedUrlField = textField()
                    .text(existingFeedUrl ?: "")
                    .columns(50)
                    .comment("Example: https://api.nuget.org/v3/index.json")
                    .component.apply {
                        isEditable = true
                        selectAll()
                    }
            }

            row("Username:") {
                usernameField = textField()
                    .text(existingCredentials?.username ?: "")
                    .columns(30)
                    .comment("Your username or email for this feed")
                    .component.apply {
                        isEditable = true
                    }
            }

            row("Password:") {
                passwordField = passwordField()
                    .text(existingCredentials?.password ?: "")
                    .columns(30)
                    .comment("Your password or API key")
                    .component.apply {
                        isEditable = true
                    }
            }

            row {
                autoSubmitCheckbox = checkBox("Auto-submit credentials")
                    .selected(existingCredentials?.autoSubmit ?: false)
                    .comment("Automatically submit the form after filling credentials")
                    .component
            }

            group("Quick Setup") {
                row {
                    button("NuGet.org") { setupNuGetOrg() }
                    button("Azure DevOps") { setupAzureDevOps() }
                    button("GitHub Packages") { setupGitHubPackages() }
                }
            }
        }.apply {
            preferredSize = Dimension(500, 300)
        }
    }

    override fun doValidate(): ValidationInfo? {
        val feedUrl = feedUrlField.text.trim()
        if (feedUrl.isEmpty()) {
            return ValidationInfo("Feed URL is required", feedUrlField)
        }

        if (!isValidUrl(feedUrl)) {
            return ValidationInfo("Please enter a valid URL (must start with http:// or https://)", feedUrlField)
        }

        val username = usernameField.text.trim()
        if (username.isEmpty()) {
            return ValidationInfo("Username is required", usernameField)
        }

        val password = String(passwordField.password)
        if (password.isEmpty()) {
            return ValidationInfo("Password is required", passwordField)
        }

        return null
    }

    override fun doOKAction() {
        val feedUrl = feedUrlField.text.trim()
        val username = usernameField.text.trim()
        val password = String(passwordField.password)
        val autoSubmit = autoSubmitCheckbox.isSelected

        result = NuGetCredentials(
            username = username,
            password = password,
            feedUrl = feedUrl,
            autoSubmit = autoSubmit
        )

        super.doOKAction()
    }

    fun getCredentials(): NuGetCredentials? = result

    private fun setupNuGetOrg() {
        feedUrlField.text = NUGET_ORG_URL
        usernameField.requestFocusInWindow()
    }

    private fun setupAzureDevOps() {
        val org = Messages.showInputDialog(
            "Enter your Azure DevOps organization name:",
            "Azure DevOps Setup",
            Messages.getQuestionIcon()
        )

        if (!org.isNullOrBlank()) {
            val feed = Messages.showInputDialog(
                "Enter your feed name:",
                "Azure DevOps Setup",
                Messages.getQuestionIcon()
            )

            if (!feed.isNullOrBlank()) {
                feedUrlField.text = buildAzureDevOpsUrl(org, feed)
                usernameField.requestFocusInWindow()
            }
        }
    }

    private fun setupGitHubPackages() {
        val owner = Messages.showInputDialog(
            "Enter the GitHub username or organization:",
            "GitHub Packages Setup",
            Messages.getQuestionIcon()
        )

        if (!owner.isNullOrBlank()) {
            feedUrlField.text = buildGitHubPackagesUrl(owner)
            usernameField.text = owner
            passwordField.requestFocusInWindow()
        }
    }

    private fun setupFocusValidationClearing() {
        val focusListener = object : FocusAdapter() {
            override fun focusLost(e: FocusEvent?) {
                // Clear validation when field loses focus
                SwingUtilities.invokeLater {
                    // This triggers a revalidation
                    isOKActionEnabled = true
                }
            }
        }

        // Add focus listeners to all input fields
        feedUrlField.addFocusListener(focusListener)
        usernameField.addFocusListener(focusListener)
        passwordField.addFocusListener(focusListener)
    }
}
