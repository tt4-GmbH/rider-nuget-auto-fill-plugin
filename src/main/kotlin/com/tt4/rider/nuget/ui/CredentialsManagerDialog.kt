package com.tt4.rider.nuget.ui

import com.tt4.rider.nuget.NuGetCredentialsStore
import com.tt4.rider.nuget.NuGetDialogInterceptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.table.JBTable
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.ListSelectionModel
import javax.swing.event.TableModelEvent
import javax.swing.table.DefaultTableModel

/**
 * Dialog for managing NuGet feed credentials
 */
class CredentialsManagerDialog(project: Project?) : DialogWrapper(project, true) {

    private val credentialStore = NuGetCredentialsStore.getInstance()
    private val interceptor = NuGetDialogInterceptor.getInstance()
    private lateinit var tableModel: DefaultTableModel
    private lateinit var table: JBTable
    private lateinit var addButton: JButton
    private lateinit var editButton: JButton
    private lateinit var removeButton: JButton
    private lateinit var testButton: JButton
    private lateinit var statusLabel: JLabel

    init {
        title = "NuGet Auto-Fill - Manage Credentials"
        init()
        loadCredentials()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row {
                label("Manage your NuGet feed credentials").apply {
                    this.component.font = this.component.font.deriveFont(16f)
                }
            }

            row {
                label("Status: ")
                statusLabel = label(getStatusText()).component
            }

            row {
                cell(createTablePanel())
                    .align(AlignX.FILL)
                    .resizableColumn()
            }.resizableRow()

            row {
                addButton = button("Add Feed") { addFeed() }.component
                editButton = button("Edit") { editFeed() }.enabled(false).component
                removeButton = button("Remove") { removeFeed() }.enabled(false).component
                testButton = button("Test") { testFeed() }.enabled(false).component
            }

            row {
                button("Manual Check") { manualCheck() }
                    .comment("Manually check for NuGet dialogs (for testing)")
            }

            row {
                button("Clear All") { clearAll() }
                    .comment("Remove all stored credentials")
            }
        }.apply {
            preferredSize = Dimension(700, 500)
        }
    }

    private fun createTablePanel(): JComponent {
        // Create table model with proper constructor
        tableModel = object : DefaultTableModel(
            arrayOf("Feed URL", "Username", "Auto-Submit", "Enabled"),
            0
        ) {
            override fun isCellEditable(row: Int, column: Int): Boolean {
                return column == 2 || column == 3 // Only Auto-Submit and Enabled columns
            }

            override fun getColumnClass(columnIndex: Int): Class<*> {
                return when (columnIndex) {
                    2, 3 -> Boolean::class.java
                    else -> String::class.java
                }
            }
        }

        // Create table
        table = JBTable(tableModel).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

            // Set column widths
            columnModel.getColumn(0).preferredWidth = 300 // URL
            columnModel.getColumn(1).preferredWidth = 150 // Username
            columnModel.getColumn(2).preferredWidth = 100 // Auto-Submit
            columnModel.getColumn(3).preferredWidth = 80  // Enabled
        }

        // Setup selection listener
        table.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val hasSelection = table.selectedRow >= 0
                editButton.isEnabled = hasSelection
                removeButton.isEnabled = hasSelection
                testButton.isEnabled = hasSelection
            }
        }

        // Handle table edits
        tableModel.addTableModelListener { e ->
            if (e.type == TableModelEvent.UPDATE) {
                val row = e.firstRow
                val column = e.column

                if (row >= 0 && row < tableModel.rowCount) {
                    val feedUrl = tableModel.getValueAt(row, 0) as String

                    when (column) {
                        2 -> { // Auto-Submit column
                            val autoSubmit = tableModel.getValueAt(row, 2) as Boolean
                            credentialStore.setAutoSubmit(feedUrl, autoSubmit)
                        }
                        3 -> { // Enabled column
                            val enabled = tableModel.getValueAt(row, 3) as Boolean
                            credentialStore.setFeedEnabled(feedUrl, enabled)
                        }
                    }
                }
            }
        }

        return JBScrollPane(table).apply {
            preferredSize = Dimension(650, 300)
        }
    }

    private fun addFeed() {
        val dialog = FeedCredentialsDialog(this, null, null)
        if (dialog.showAndGet()) {
            val credentials = dialog.getCredentials() ?: return
            // PasswordSafe.set() is blocking I/O — must not be called on EDT
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    credentialStore.storeCredentials(credentials)
                    ApplicationManager.getApplication().invokeLater {
                        loadCredentials()
                        Messages.showInfoMessage(
                            "Credentials stored successfully for: ${credentials.feedUrl}",
                            "Success"
                        )
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            "Failed to store credentials for: ${credentials.feedUrl}\n\n${e.message}\n\nPlease check your system keychain settings.",
                            "Storage Error"
                        )
                    }
                }
            }
        }
    }

    private fun editFeed() {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return

        val feedUrl = tableModel.getValueAt(selectedRow, 0) as String

        // PasswordSafe.get() is blocking I/O — load existing credentials on background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            val existingCredentials = credentialStore.getCredentials(feedUrl)
            ApplicationManager.getApplication().invokeLater {
                val dialog = FeedCredentialsDialog(this, feedUrl, existingCredentials)
                if (dialog.showAndGet()) {
                    val credentials = dialog.getCredentials() ?: return@invokeLater
                    // PasswordSafe.set() is blocking I/O — must not be called on EDT
                    ApplicationManager.getApplication().executeOnPooledThread {
                        try {
                            credentialStore.storeCredentials(credentials)
                            ApplicationManager.getApplication().invokeLater {
                                loadCredentials()
                                Messages.showInfoMessage(
                                    "Credentials updated successfully for: ${credentials.feedUrl}",
                                    "Success"
                                )
                            }
                        } catch (e: Exception) {
                            ApplicationManager.getApplication().invokeLater {
                                Messages.showErrorDialog(
                                    "Failed to store credentials for: ${credentials.feedUrl}\n\n${e.message}\n\nPlease check your system keychain settings.",
                                    "Storage Error"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun removeFeed() {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return

        val feedUrl = tableModel.getValueAt(selectedRow, 0) as String

        val result = Messages.showYesNoDialog(
            "Remove credentials for:\n$feedUrl\n\nThis cannot be undone.",
            "Confirm Removal",
            Messages.getQuestionIcon()
        )

        if (result == Messages.YES) {
            credentialStore.removeCredentials(feedUrl)
            loadCredentials()
            Messages.showInfoMessage("Credentials removed for: $feedUrl", "Removed")
        }
    }

    private fun testFeed() {
        val selectedRow = table.selectedRow
        if (selectedRow < 0) return

        val feedUrl = tableModel.getValueAt(selectedRow, 0) as String

        // PasswordSafe.get() is blocking I/O — must not be called on EDT
        ApplicationManager.getApplication().executeOnPooledThread {
            val credentials = credentialStore.getCredentials(feedUrl)
            ApplicationManager.getApplication().invokeLater {
                if (credentials != null) {
                    val success = credentialStore.testCredentials(credentials)
                    val message = if (success) {
                        "✅ Connection test successful for:\n$feedUrl"
                    } else {
                        "❌ Connection test failed for:\n$feedUrl\n\nPlease verify your credentials."
                    }
                    Messages.showMessageDialog(
                        message,
                        "Test Result",
                        if (success) Messages.getInformationIcon() else Messages.getErrorIcon()
                    )
                } else {
                    Messages.showErrorDialog(
                        "No credentials found for:\n$feedUrl\n\nThe password could not be retrieved from the system keychain.\nTry removing and re-adding this feed.",
                        "Test Failed"
                    )
                }
            }
        }
    }

    private fun loadCredentials() {
        tableModel.rowCount = 0

        val feeds = credentialStore.getAllFeeds()
        for (feed in feeds) {
            tableModel.addRow(
                arrayOf<Any>(
                    feed.feedUrl,
                    feed.username,
                    feed.autoSubmit,
                    feed.enabled
                )
            )
        }

        statusLabel.text = getStatusText()
    }

    private fun manualCheck() {
        interceptor.triggerManualCheck()
        Messages.showInfoMessage(
            "Manual dialog check completed.\nCheck the IDE log for details.",
            "Manual Check"
        )
    }

    private fun getStatusText(): String {
        val running = if (interceptor.isRunning()) "🟢 Active" else "🔴 Stopped"
        val credCount = credentialStore.getAllFeeds().size
        return "Interceptor: $running | Stored feeds: $credCount"
    }

    private fun clearAll() {
        val feeds = credentialStore.getAllFeeds()
        if (feeds.isEmpty()) {
            Messages.showInfoMessage("No credentials to clear.", "Nothing to Clear")
            return
        }

        val result = Messages.showYesNoDialog(
            "This will permanently remove ALL stored NuGet credentials (${feeds.size} feeds).\n\nAre you sure?",
            "Clear All Credentials",
            Messages.getWarningIcon()
        )

        if (result == Messages.YES) {
            credentialStore.clearAllCredentials()
            loadCredentials()
            Messages.showInfoMessage("All credentials have been cleared.", "Cleared")
        }
    }
}
