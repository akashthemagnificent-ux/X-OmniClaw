/**
 * OmniClaw Source Reference:
 * - ../omniclaw/src/gateway/(all)
 *
 * OmniClaw adaptation: Android UI layer.
 */
package com.shijing.xomniclaw.ui.activity

import android.content.Intent
import android.net.Uri
import android.content.ComponentName
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.shijing.xomniclaw.core.MyApplication
import com.shijing.xomniclaw.accessibility.AccessibilityProxy
import com.shijing.xomniclaw.util.MMKVKeys
import com.shijing.xomniclaw.R
import com.shijing.xomniclaw.databinding.ActivityMainBinding
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.launch
import com.shijing.xomniclaw.agent.skills.SkillsLoader
import com.shijing.xomniclaw.gateway.GatewayController
import com.shijing.xomniclaw.ui.session.SessionManager
import com.shijing.xomniclaw.updater.AppUpdater
import java.io.File

/**
 * OmniClaw Main Activity
 *
 * Maps OmniClaw CLI commands to visual interface:
 * - omniclaw status → Status cards
 * - omniclaw config → Config page
 * - omniclaw skills → Skills management
 * - omniclaw gateway → Gateway control
 * - omniclaw sessions → Session list
 */
class MainActivity : AppCompatActivity() {

    private fun launchObserverPermissionActivity() {
        try {
            startActivity(Intent().apply {
                component = ComponentName(
                    "com.shijing.xomniclaw",
                    "com.shijing.xomniclaw.accessibility.PermissionActivity"
                )
            })
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Observer PermissionActivity unavailable, fallback to local PermissionsActivity", e)
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }

    private lateinit var binding: ActivityMainBinding
    private val mmkv by lazy { MMKV.defaultMMKV() }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_ACCESSIBILITY = 1001
        private const val REQUEST_OVERLAY = 1002
        private const val REQUEST_SCREEN_CAPTURE = 1003
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
        updateStatusCards()
    }

    override fun onResume() {
        super.onResume()
        updateStatusCards()
        silentUpdateCheck()
    }

    /**
     * Silent update check on every app resume (cold + warm start).
     * Only shows dialog if update is available, no toast on "already latest".
     */
    private fun silentUpdateCheck() {
        lifecycleScope.launch {
            try {
                val updater = AppUpdater(this@MainActivity)
                val info = updater.checkForUpdate()
                if (info.hasUpdate) {
                    showUpdateDialog(updater, info)
                }
            } catch (_: Exception) {
                // Silent — don't bother user on network errors
            }
        }
    }

    private fun setupViews() {
        // Status card click events
        binding.apply {
            // Gateway card
            cardGateway.setOnClickListener {
                if (isGatewayRunning()) {
                    showGatewayInfo()
                } else {
                    Toast.makeText(this@MainActivity, "Gateway is not running", Toast.LENGTH_SHORT).show()
                }
            }

            // Permissions card
            cardPermissions.setOnClickListener {
                launchObserverPermissionActivity()
            }

            // Skills card
            cardSkills.setOnClickListener {
                showSkillsDialog()
            }

            // Sessions card
            cardSessions.setOnClickListener {
                showSessionsDialog()
            }

            // Bottom navigation buttons
            btnConfig.setOnClickListener {
                startActivity(Intent(this@MainActivity, ConfigActivity::class.java))
            }

            btnTest.setOnClickListener {
                checkForUpdate()
            }

            btnLogs.setOnClickListener {
                showLogsDialog()
            }
        }
    }

    /**
     * Update status cards
     * Maps to OmniClaw CLI: omniclaw status
     */
    private fun updateStatusCards() {
        lifecycleScope.launch {
            updateGatewayCard()
            updatePermissionsCard()
            updateSkillsCard()
            updateSessionsCard()
        }
    }

    /**
     * Update Gateway status card
     */
    private fun updateGatewayCard() {
        val isRunning = isGatewayRunning()
        binding.apply {
            tvGatewayStatus.text = if (isRunning) "Running" else "Not running"
            tvGatewayStatus.setTextColor(
                if (isRunning) getColor(R.color.status_ok)
                else getColor(R.color.status_error)
            )

            if (isRunning) {
                tvGatewayDetails.text = "WebSocket: ws://0.0.0.0:8765\n" +
                        "Sessions: ${getSessionCount()}"
            } else {
                tvGatewayDetails.text = "Gateway service is not started"
            }
        }
    }

    /**
     * Update permissions status card
     */
    private fun updatePermissionsCard() {
        val accessibility = AccessibilityProxy.isConnected.value == true && AccessibilityProxy.isServiceReady()
        val overlay = Settings.canDrawOverlays(this)
        val screenCapture = AccessibilityProxy.isMediaProjectionGranted()

        val allGranted = accessibility && overlay && screenCapture

        binding.apply {
            tvPermissionsStatus.text = if (allGranted) "Granted" else "Permission required"
            tvPermissionsStatus.setTextColor(
                if (allGranted) getColor(R.color.status_ok)
                else getColor(R.color.status_warning)
            )

            tvPermissionsDetails.text = buildString {
                append("Accessibility: ${if (accessibility) "✓" else "✗"}\n")
                append("Overlay: ${if (overlay) "✓" else "✗"}\n")
                append("Screen capture: ${if (screenCapture) "✓" else "✗"} (${AccessibilityProxy.getMediaProjectionStatus()})")
            }
        }
    }

    /**
     * Update Skills status card
     */
    private fun updateSkillsCard() {
        try {
            val skillsLoader = SkillsLoader(this)
            val allSkills = skillsLoader.getAllSkills()
            val alwaysSkills = skillsLoader.getAlwaysSkills()
            val totalSkills = allSkills.size

            binding.apply {
                tvSkillsStatus.text = "$totalSkills Skills"
                tvSkillsStatus.setTextColor(getColor(R.color.status_ok))

                tvSkillsDetails.text = buildString {
                    append("Always: ${alwaysSkills.size}\n")
                    append("On-Demand: ${totalSkills - alwaysSkills.size}\n")
                    append("Total: $totalSkills")
                }
            }
        } catch (e: Exception) {
            binding.tvSkillsStatus.text = "Failed to load"
            binding.tvSkillsDetails.text = e.message ?: "Unknown error"
        }
    }

    /**
     * Update Sessions status card
     */
    private fun updateSessionsCard() {
        val sessionCount = getSessionCount()

        binding.apply {
            tvSessionsStatus.text = if (sessionCount > 0) {
                "$sessionCount active sessions"
            } else {
                "No active sessions"
            }
            tvSessionsStatus.setTextColor(
                if (sessionCount > 0) getColor(R.color.status_ok)
                else getColor(R.color.text_secondary)
            )

            tvSessionsDetails.text = if (sessionCount > 0) {
                "Tap to view details"
            } else {
                "No active Agent sessions"
            }
        }
    }

    /**
     * Show Gateway detailed information
     * Maps to OmniClaw CLI: omniclaw gateway status
     */
    private fun showGatewayInfo() {
        val info = buildString {
            append("Gateway Status\n\n")
            append("WebSocket port: 8765\n")
            append("Connection URL: ws://0.0.0.0:8765\n")
            append("Active Sessions: ${getSessionCount()}\n\n")
            append("RPC methods:\n")
            append("  • agent - Run Agent tasks\n")
            append("  • agent.wait - Wait for task completion\n")
            append("  • health - Health check\n")
            append("  • session.list - List sessions\n")
            append("  • session.reset - Reset session\n")
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Gateway Information")
            .setMessage(info)
            .setPositiveButton("Close", null)
            .setNeutralButton("Test Connection") { _, _ ->
                Toast.makeText(this, if (isGatewayRunning()) "Gateway is running normally ✅" else "Gateway is not running ❌", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * Show permissions dialog
     */
    private fun showPermissionsDialog() {
        val accessibility = AccessibilityProxy.isConnected.value == true && AccessibilityProxy.isServiceReady()
        val overlay = Settings.canDrawOverlays(this)
        val screenCapture = AccessibilityProxy.isMediaProjectionGranted()

        val message = buildString {
            append("Permission status:\n\n")
            append("${if (accessibility) "✓" else "✗"} Accessibility service\n")
            if (!accessibility) {
                append("  Used for: tapping, swiping, typing\n\n")
            }
            append("${if (overlay) "✓" else "✗"} Overlay permission\n")
            if (!overlay) {
                append("  Used for: showing Agent status\n\n")
            }
            append("${if (screenCapture) "✓" else "✗"} Screen capture permission\n")
            if (!screenCapture) {
                append("  Used for: screenshotting the screen\n")
                append("  Status: ${AccessibilityProxy.getMediaProjectionStatus()}\n")
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Permission Management")
            .setMessage(message)
            .setPositiveButton("Go to Settings") { _, _ ->
                requestPermissions()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Request permissions
     */
    private fun requestPermissions() {
        val accessibility = AccessibilityProxy.isConnected.value == true && AccessibilityProxy.isServiceReady()
        val overlay = Settings.canDrawOverlays(this)
        val screenCapture = AccessibilityProxy.isMediaProjectionGranted()

        when {
            !accessibility -> {
                // Open accessibility settings
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivityForResult(intent, REQUEST_ACCESSIBILITY)
            }
            !overlay -> {
                // Request overlay permission
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
                startActivityForResult(intent, REQUEST_OVERLAY)
            }
            !screenCapture -> {
                // Screen recording permission managed by accessibility service APK
                Toast.makeText(
                    this,
                    "Screen capture permission is managed by the accessibility service APK\nPlease grant it in system settings",
                    Toast.LENGTH_LONG
                ).show()
            }
            else -> {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Check if Gateway is running
     */
    private fun isGatewayRunning(): Boolean {
        return try {
            val app = application as? MyApplication
            java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress("127.0.0.1", 8765), 500); true }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get active Session count
     */
    private fun getSessionCount(): Int {
        return try {
            val app = application as? MyApplication
            SessionManager().getSessionCount()
        } catch (e: Exception) {
            0
        }
    }


    /**
     * Show Skills management dialog
     * Maps to: omniclaw skills
     */
    private fun showSkillsDialog() {
        try {
            val skillsLoader = SkillsLoader(this)
            val allSkills = skillsLoader.getAllSkills()

            val message = buildString {
                if (allSkills.isEmpty()) {
                    append("No Skills installed")
                } else {
                    allSkills.forEachIndexed { index, skill ->
                        val emoji = skill.metadata.emoji ?: "📋"
                        val always = if (skill.metadata.always) " [Always]" else ""
                        append("${index + 1}. $emoji ${skill.name}$always\n")
                        append("   ${skill.description.lines().first().take(50)}\n\n")
                    }
                }
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Skills Management (${allSkills.size})")
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load Skills: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Show Sessions list dialog
     * Maps to: omniclaw sessions
     */
    private fun showSessionsDialog() {
        try {
            val sessionManager = SessionManager()
            val sessions = sessionManager.getAllSessions()

            val message = buildString {
                if (sessions.isEmpty()) {
                    append("No active sessions")
                } else {
                    sessions.forEachIndexed { index, session ->
                        append("${index + 1}. ${session.title}\n")
                        append("   Messages: ${session.messages.size}\n\n")
                    }
                }
            }

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Session List (${sessions.size})")
                .setMessage(message)
                .setPositiveButton("Close", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to load sessions: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Show Logs viewer dialog
     * Maps to: viewing AgentLoop session logs
     */
    private fun showLogsDialog() {
        val logDir = File("/sdcard/.xomniclaw/workspace/logs")
        if (!logDir.exists() || !logDir.isDirectory) {
            Toast.makeText(this, "No log files", Toast.LENGTH_SHORT).show()
            return
        }

        val logFiles = logDir.listFiles()
            ?.filter { it.name.endsWith(".log") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(20)
            ?: emptyList()

        if (logFiles.isEmpty()) {
            Toast.makeText(this, "No log files", Toast.LENGTH_SHORT).show()
            return
        }

        val fileNames = logFiles.map { file ->
            val sizeKb = file.length() / 1024
            "${file.name} (${sizeKb}KB)"
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("AgentLoop Logs (${logFiles.size})")
            .setItems(fileNames) { _, which ->
                showLogContent(logFiles[which])
            }
            .setPositiveButton("Close", null)
            .show()
    }

    /**
     * Show specific log file content
     */
    private fun showLogContent(file: File) {
        try {
            val content = file.readText()
            val truncated = if (content.length > 5000) {
                content.take(5000) + "\n\n... (${content.length - 5000} chars truncated)"
            } else content

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(file.name)
                .setMessage(truncated)
                .setPositiveButton("Close", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to read log: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Check for app updates from GitHub Releases
     */
    private fun checkForUpdate() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            try {
                val updater = AppUpdater(this@MainActivity)
                val info = updater.checkForUpdate()

                if (info.hasUpdate) {
                    showUpdateDialog(updater, info)
                } else {
                    Toast.makeText(
                        this@MainActivity,
                        "You're on the latest version v${info.currentVersion}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Update check failed: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Show update available dialog
     */
    private fun showUpdateDialog(updater: AppUpdater, info: AppUpdater.UpdateInfo) {
        val sizeStr = if (info.fileSize > 0) {
            "%.1f MB".format(info.fileSize / 1024.0 / 1024.0)
        } else "Unknown size"

        val message = buildString {
            append("New version available!\n\n")
            append("Current version: v${info.currentVersion}\n")
            append("Latest version: v${info.latestVersion}\n")
            append("File size: $sizeStr\n")
            if (!info.publishedAt.isNullOrEmpty()) {
                append("Release date: ${info.publishedAt.take(10)}\n")
            }
            if (!info.releaseNotes.isNullOrEmpty()) {
                append("\nWhat's new:\n${info.releaseNotes.take(300)}")
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("New version available v${info.latestVersion}")
            .setMessage(message)
            .setPositiveButton("Update Now") { _, _ ->
                if (info.downloadUrl != null) {
                    Toast.makeText(this, "Starting download…", Toast.LENGTH_SHORT).show()
                    lifecycleScope.launch {
                        val success = updater.downloadAndInstall(info.downloadUrl, info.latestVersion)
                        if (!success) {
                            // Fallback: open browser
                            openUrl(info.releaseUrl)
                        }
                    }
                } else {
                    // No direct download URL, open GitHub releases page
                    openUrl(info.releaseUrl)
                }
            }
            .setNeutralButton("Open in Browser") { _, _ ->
                openUrl(info.releaseUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    /**
     * Open URL in browser
     */
    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Unable to open browser: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_ACCESSIBILITY, REQUEST_OVERLAY -> {
                // Returned from permission settings, refresh status
                updateStatusCards()
            }
        }
    }
}
