package com.shijing.xomniclaw.accessibility

/**
 * Upstream reference (OmniClaw):
 * - ../omniclaw/src/gateway/(all)
 *
 * X-OmniClaw adaptation: observer permission and projection flow.
 */


import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.widget.Toast
import com.shijing.xomniclaw.accessibility.databinding.ActivityObserverPermissionsBinding
import com.shijing.xomniclaw.accessibility.service.AccessibilityBinderService
import kotlinx.coroutines.*
import java.io.File

/**
 * 权限请求 Activity (重构版)
 *
 * 主要改进:
 * 1. 异步权限检查 (不阻塞主线程)
 * 2. 降低检查频率 (1秒 -> 2秒)
 * 3. 事件驱动 UI 更新
 * 4. 添加详细状态说明
 * 5. 优化用户体验
 */
class PermissionActivity : Activity() {
    companion object {
        private const val TAG = "PermissionActivity"
        private const val REQUEST_CODE_MEDIA_PROJECTION = 10086
        private const val REQUEST_CODE_ACCESSIBILITY = 1001
        private const val REQUEST_CODE_MANAGE_STORAGE = 1002
        /** 摄像头 + 麦克风运行时权限（与主界面语音/视觉一致） */
        private const val REQUEST_CODE_CAMERA_MIC = 1003
        /** 相册读取权限（Android 13+ READ_MEDIA_IMAGES；低版本 READ_EXTERNAL_STORAGE） */
        private const val REQUEST_CODE_ALBUM = 1004
        private const val STATUS_CHECK_INTERVAL = 2000L  // 2秒检查一次 (降低频率)
    }

    private lateinit var binding: ActivityObserverPermissionsBinding
    private val mainHandler = Handler(Looper.getMainLooper())

    // Coroutine scope for this activity
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 状态缓存 (避免频繁检查)
    private var cachedAccessibilityEnabled = false
    private var cachedMediaProjectionAuthorized = false
    private var cachedStorageGranted = false
    private var cachedAlbumGranted = false
    private var cachedCameraGranted = false
    private var cachedMicrophoneGranted = false
    private var lastCheckTime = 0L

    // Status check job
    private var statusCheckJob: Job? = null

    private data class PermissionCheckSnapshot(
        val settingsEnabled: Boolean,
        val serviceInstancePresent: Boolean,
        val rootPresent: Boolean,
        val accessibilityEnabled: Boolean,
        val mediaProjectionAuthorized: Boolean,
        val storageGranted: Boolean,
        val albumGranted: Boolean,
        val cameraGranted: Boolean,
        val microphoneGranted: Boolean
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")

        // 初始化 MediaProjectionHelper
        val workspace = File("/sdcard/.xomniclaw/workspace")
        val screenshotDir = File(workspace, "screenshots")
        MediaProjectionHelper.initialize(this, screenshotDir)

        binding = ActivityObserverPermissionsBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)

        setupViews()

        // 初始检查
        checkPermissionsAsync("onCreate")
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "lifecycle onResume")
        // 立即刷新一次，覆盖从系统设置/悬浮按钮返回但没有 onActivityResult 的场景
        checkPermissionsAsync("onResume")
        // 启动定期检查
        startStatusCheck()
        // 某些 ROM 的无障碍设置写回有延迟，再补一轮延迟刷新
        mainHandler.postDelayed({ checkPermissionsAsync("onResume-delayed-800ms") }, 800)
    }

    override fun onRestart() {
        super.onRestart()
        Log.d(TAG, "lifecycle onRestart")
        checkPermissionsAsync("onRestart")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "lifecycle onWindowFocusChanged hasFocus=$hasFocus")
        if (hasFocus) {
            checkPermissionsAsync("onWindowFocusChanged")
        }
    }

    override fun onPause() {
        super.onPause()
        // 停止定期检查
        stopStatusCheck()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel all coroutines
        activityScope.cancel()
        Log.d(TAG, "onDestroy called")
    }

    private fun setupViews() {
        binding.apply {
            // 无障碍服务按钮
            btnAccessibility.setOnClickListener {
                Log.d(TAG, "btnAccessibility clicked")
                requestAccessibilityPermission()
            }

            // 存储权限按钮
            btnStorage.setOnClickListener {
                Log.d(TAG, "btnStorage clicked")
                requestStoragePermission()
            }

            // 相册读取权限按钮
            btnAlbum.setOnClickListener {
                Log.d(TAG, "btnAlbum clicked")
                requestAlbumPermission()
            }

            // 录屏权限按钮
            btnScreenCapture.setOnClickListener {
                Log.d(TAG, "btnScreenCapture clicked")
                requestMediaProjectionPermission()
            }

            // 摄像头与麦克风（运行时权限）
            btnCameraMic.setOnClickListener {
                Log.d(TAG, "btnCameraMic clicked")
                requestCameraMicrophonePermissions()
            }

            // 一键授权按钮
            btnGrantAll.setOnClickListener {
                Log.d(TAG, "btnGrantAll clicked")
                grantAllPermissions()
            }

            // 重置按钮 (隐藏,用于调试)
            tvAllStatus.setOnLongClickListener {
                showResetDialog()
                true
            }
        }
    }

    /**
     * 异步检查权限状态
     */
    private fun checkPermissionsAsync(reason: String = "unknown") {
        activityScope.launch {
            try {
                Log.d(TAG, "checkPermissionsAsync start, reason=$reason")

                // 在后台线程检查
                val result = withContext(Dispatchers.IO) {
                    val settingsEnabled = isAccessibilityServiceEnabled()
                    val serviceInstancePresent = AccessibilityBinderService.serviceInstance != null
                    val rootPresent = AccessibilityBinderService.serviceInstance?.rootInActiveWindow != null
                    val accessibility = resolveAccessibilityEnabled()
                    val mediaProjection = MediaProjectionHelper.isAuthorized()
                    val storage = isStoragePermissionGranted()
                    val album = isAlbumPermissionGranted()
                    val cameraOk = checkSelfPermission(Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    val micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    PermissionCheckSnapshot(
                        settingsEnabled = settingsEnabled,
                        serviceInstancePresent = serviceInstancePresent,
                        rootPresent = rootPresent,
                        accessibilityEnabled = accessibility,
                        mediaProjectionAuthorized = mediaProjection,
                        storageGranted = storage,
                        albumGranted = album,
                        cameraGranted = cameraOk,
                        microphoneGranted = micOk
                    )
                }

                Log.d(
                    TAG,
                    "checkPermissionsAsync result, reason=$reason, " +
                        "settingsEnabled=${result.settingsEnabled}, " +
                        "serviceInstancePresent=${result.serviceInstancePresent}, " +
                        "rootPresent=${result.rootPresent}, " +
                        "accessibilityEnabled=${result.accessibilityEnabled}, " +
                        "mediaProjectionAuthorized=${result.mediaProjectionAuthorized}, " +
                        "storageGranted=${result.storageGranted}, albumGranted=${result.albumGranted}, " +
                        "cameraGranted=${result.cameraGranted}, microphoneGranted=${result.microphoneGranted}"
                )

                // 更新缓存
                cachedAccessibilityEnabled = result.accessibilityEnabled
                cachedMediaProjectionAuthorized = result.mediaProjectionAuthorized
                cachedStorageGranted = result.storageGranted
                cachedAlbumGranted = result.albumGranted
                cachedCameraGranted = result.cameraGranted
                cachedMicrophoneGranted = result.microphoneGranted
                lastCheckTime = System.currentTimeMillis()

                // 在主线程更新 UI
                withContext(Dispatchers.Main) {
                    updateAccessibilityUI(result.accessibilityEnabled)
                    updateMediaProjectionUI(result.mediaProjectionAuthorized)
                    updateStorageUI(result.storageGranted)
                    updateAlbumUI(result.albumGranted)
                    updateCameraMicrophoneUI(result.cameraGranted, result.microphoneGranted)
                    updateAllPermissionsUI(
                        result.accessibilityEnabled,
                        result.mediaProjectionAuthorized,
                        result.storageGranted,
                        result.albumGranted,
                        result.cameraGranted,
                        result.microphoneGranted
                    )
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error checking permissions, reason=$reason", e)
            }
        }
    }

    /**
     * 检查存储权限是否已授予
     */
    private fun isStoragePermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ 需要 MANAGE_EXTERNAL_STORAGE
            Environment.isExternalStorageManager()
        } else {
            // Android 10 及以下检查 WRITE_EXTERNAL_STORAGE
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 获取当前系统版本对应的相册读取权限列表。
     */
    private fun requiredAlbumPermissionsForCurrentSdk(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /**
     * 检查相册读取权限是否已授予。
     */
    private fun isAlbumPermissionGranted(): Boolean {
        return requiredAlbumPermissionsForCurrentSdk().all { permission ->
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 启动定期状态检查
     */
    private fun startStatusCheck() {
        stopStatusCheck()

        statusCheckJob = activityScope.launch {
            while (isActive) {
                checkPermissionsAsync("periodic")
                delay(STATUS_CHECK_INTERVAL)
            }
        }

        Log.d(TAG, "Started permission status check (interval: ${STATUS_CHECK_INTERVAL}ms)")
    }

    /**
     * 停止定期状态检查
     */
    private fun stopStatusCheck() {
        statusCheckJob?.cancel()
        statusCheckJob = null
        Log.d(TAG, "Stopped permission status check")
    }

    /**
     * 更新无障碍服务 UI
     */
    private fun updateAccessibilityUI(isEnabled: Boolean) {
        binding.apply {
            if (isEnabled) {
                tvAccessibilityStatus.text = "✅ Enabled"
                tvAccessibilityStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnAccessibility.isEnabled = false
                btnAccessibility.text = "Enabled"
                btnAccessibility.alpha = 0.5f

                tvAccessibilityDesc.text = """
                    ✅ Accessibility service enabled

                    Features:
                    • Tap, swipe, long press
                    • Enter text
                    • Read screen information
                    • Navigation (Home/Back)
                """.trimIndent()
            } else {
                tvAccessibilityStatus.text = "❌ Not enabled"
                tvAccessibilityStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                btnAccessibility.isEnabled = true
                btnAccessibility.text = "Go to Settings"
                btnAccessibility.alpha = 1.0f

                tvAccessibilityDesc.text = """
                    ⚠️ Accessibility service needs to be enabled

                    Steps:
                    1. Tap the "Go to Settings" button
                    2. Select downloaded apps and find X-OmniClaw
                    3. Turn on the service switch
                    4. Grant the permission
                """.trimIndent()
            }
        }
    }

    /**
     * 更新录屏权限 UI
     */
    private fun updateMediaProjectionUI(isAuthorized: Boolean) {
        val statusDetails = MediaProjectionHelper.getDetailedStatus()

        binding.apply {
            if (isAuthorized) {
                tvScreenCaptureStatus.text = "✅ Authorized"
                tvScreenCaptureStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnScreenCapture.isEnabled = false
                btnScreenCapture.text = "Authorized"
                btnScreenCapture.alpha = 0.5f

                tvScreenCaptureDesc.text = """
                    ✅ Screen recording permission granted

                    Status: $statusDetails

                    Features:
                    • Capture the screen
                    • Analyze UI elements
                    • Assist Agent observation
                """.trimIndent()
            } else {
                tvScreenCaptureStatus.text = "❌ Not authorized"
                tvScreenCaptureStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                btnScreenCapture.isEnabled = true
                btnScreenCapture.text = "Grant Permission"
                btnScreenCapture.alpha = 1.0f

                tvScreenCaptureDesc.text = """
                    ⚠️ Screen recording permission needs to be granted

                    Status: $statusDetails

                    Steps:
                    1. Tap the "Grant Permission" button
                    2. Select "Entire screen", then tap Next
                    3. Tap "Start now" in the dialog
                    4. The foreground service will start automatically

                    Note: Screen recording permission requires the foreground service to stay active
                """.trimIndent()
            }
        }
    }

    /**
     * 更新存储权限 UI
     */
    private fun updateStorageUI(isGranted: Boolean) {
        binding.apply {
            if (isGranted) {
                tvStorageStatus.text = "✅ Authorized"
                tvStorageStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnStorage.isEnabled = false
                btnStorage.text = "Authorized"
                btnStorage.alpha = 0.5f

                tvStorageDesc.text = """
                    ✅ Storage permission granted

                    Features:
                    • Save screenshot files
                    • Access the workspace
                    • Read and write config files
                """.trimIndent()
            } else {
                tvStorageStatus.text = "❌ Not authorized"
                tvStorageStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                btnStorage.isEnabled = true
                btnStorage.text = "Grant Permission"
                btnStorage.alpha = 1.0f

                tvStorageDesc.text = """
                    ⚠️ Storage permission needs to be granted

                    Details:
                    • Android 11+ requires "All files access"
                    • Tap the "Grant Permission" button
                    • Enable the permission in Settings

                    Note: Storage permission is used to save screenshots
                """.trimIndent()
            }
        }
    }

    /**
     * 更新相册读取权限 UI。
     */
    private fun updateAlbumUI(isGranted: Boolean) {
        binding.apply {
            if (isGranted) {
                tvAlbumStatus.text = "✅ Authorized"
                tvAlbumStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnAlbum.isEnabled = false
                btnAlbum.text = "Authorized"
                btnAlbum.alpha = 0.5f
                tvAlbumDesc.text = """
                    ✅ Gallery read permission granted

                    Features:
                    • Read images from the system gallery
                    • Support gallery search/copy and similar capabilities
                """.trimIndent()
            } else {
                tvAlbumStatus.text = "❌ Not authorized"
                tvAlbumStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                btnAlbum.isEnabled = true
                btnAlbum.text = "Grant Gallery Permission"
                btnAlbum.alpha = 1.0f
                tvAlbumDesc.text = """
                    ⚠️ Gallery read permission needs to be granted

                    Details:
                    • Android 13+ requests image access from "Photos and videos"
                    • Android 12 and below uses the read storage permission
                """.trimIndent()
            }
        }
    }

    /**
     * 更新摄像头、麦克风 UI（两者都授予才显示总成功）
     */
    private fun updateCameraMicrophoneUI(cameraGranted: Boolean, microphoneGranted: Boolean) {
        val both = cameraGranted && microphoneGranted
        binding.apply {
            if (both) {
                tvCameraMicStatus.text = "✅ Authorized"
                tvCameraMicStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnCameraMic.isEnabled = false
                btnCameraMic.text = "Authorized"
                btnCameraMic.alpha = 0.5f
                tvCameraMicDesc.text = """
                    ✅ Camera and microphone granted

                    Features:
                    • Camera preview and streaming
                    • Voice input, press and hold to talk
                """.trimIndent()
            } else {
                val camText = if (cameraGranted) "✅" else "❌"
                val micText = if (microphoneGranted) "✅" else "❌"
                tvCameraMicStatus.text = "Camera $camText  Microphone $micText"
                tvCameraMicStatus.setTextColor(getColor(android.R.color.holo_red_dark))
                btnCameraMic.isEnabled = true
                btnCameraMic.text = "Grant Camera and Microphone"
                btnCameraMic.alpha = 1.0f
                tvCameraMicDesc.text = """
                    ⚠️ Camera and microphone permissions are required

                    Details:
                    • After tapping the button, allow the permissions in the system dialog
                    • You can also use "Grant All" to prompt automatically once Accessibility/Storage/Screen recording are granted
                """.trimIndent()
            }
        }
    }

    /**
     * 更新总体状态 UI（5 项：无障碍、录屏、存储、摄像头、麦克风）
     */
    private fun updateAllPermissionsUI(
        accessibilityEnabled: Boolean,
        mediaProjectionAuthorized: Boolean,
        storageGranted: Boolean,
        albumGranted: Boolean,
        cameraGranted: Boolean,
        microphoneGranted: Boolean
    ) {
        val camMicComplete = cameraGranted && microphoneGranted
        val allGranted = accessibilityEnabled &&
            mediaProjectionAuthorized &&
            storageGranted &&
            albumGranted &&
            camMicComplete
        val grantedCount = listOf(
            accessibilityEnabled,
            mediaProjectionAuthorized,
            storageGranted,
            albumGranted,
            cameraGranted,
            microphoneGranted
        ).count { it }

        binding.apply {
            if (allGranted) {
                tvAllStatus.text = "✅ All permissions granted (6/6)"
                tvAllStatus.setTextColor(getColor(android.R.color.holo_green_dark))
                btnGrantAll.isEnabled = false
                btnGrantAll.text = "All Granted"
                btnGrantAll.alpha = 0.5f
            } else {
                tvAllStatus.text = "⚠️ Granted $grantedCount/6 permissions"
                tvAllStatus.setTextColor(getColor(android.R.color.holo_orange_dark))
                btnGrantAll.isEnabled = true
                btnGrantAll.text = "Grant All (${grantedCount}/6)"
                btnGrantAll.alpha = 1.0f
            }
        }
    }

    /**
     * 请求无障碍服务权限
     */
    private fun requestAccessibilityPermission() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivityForResult(intent, REQUEST_CODE_ACCESSIBILITY)
            Toast.makeText(this, "Please find and enable the accessibility service", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings", e)
            Toast.makeText(this, "Unable to open accessibility settings", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 请求录屏权限
     */
    private fun requestMediaProjectionPermission() {
        try {
            val needsPermission = !MediaProjectionHelper.requestPermission(this)

            if (!needsPermission) {
                Toast.makeText(this, "Screen recording permission granted", Toast.LENGTH_SHORT).show()
                checkPermissionsAsync("requestMediaProjectionPermission-alreadyGranted")
            } else {
                Toast.makeText(this, "Please select \"Entire screen\" and tap Next, then tap \"Start now\" in the dialog", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request media projection", e)
            Toast.makeText(this, "Failed to request screen recording permission: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 请求存储权限
     */
    private fun requestStoragePermission() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+ 需要跳转到 MANAGE_EXTERNAL_STORAGE 设置
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE)
                Toast.makeText(this, "Please enable the \"Allow management of all files\" permission", Toast.LENGTH_LONG).show()
            } else {
                // Android 10 及以下直接请求 WRITE_EXTERNAL_STORAGE
                requestPermissions(
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_CODE_MANAGE_STORAGE
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request storage permission", e)
            Toast.makeText(this, "Failed to request storage permission: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 请求相册读取权限（按 Android 版本选择权限名）。
     */
    private fun requestAlbumPermission() {
        try {
            val need = requiredAlbumPermissionsForCurrentSdk().filter { permission ->
                checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
            }
            if (need.isEmpty()) {
                Toast.makeText(this, "Gallery permission already granted", Toast.LENGTH_SHORT).show()
                checkPermissionsAsync("requestAlbum-alreadyOk")
                return
            }
            requestPermissions(need.toTypedArray(), REQUEST_CODE_ALBUM)
            Toast.makeText(this, "Please allow gallery read access in the system dialog", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request album permission", e)
            Toast.makeText(this, "Failed to request gallery permission: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 请求摄像头与麦克风（合并一次系统对话框，按需只申请未授权的）
     */
    private fun requestCameraMicrophonePermissions() {
        try {
            val need = mutableListOf<String>()
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.CAMERA)
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.RECORD_AUDIO)
            }
            if (need.isEmpty()) {
                Toast.makeText(this, "Camera and microphone permissions already granted", Toast.LENGTH_SHORT).show()
                checkPermissionsAsync("requestCameraMic-alreadyOk")
                return
            }
            requestPermissions(need.toTypedArray(), REQUEST_CODE_CAMERA_MIC)
            Toast.makeText(this, "Please allow camera and microphone in the system dialog", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request camera/microphone permissions", e)
            Toast.makeText(this, "Failed to request camera/microphone access: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 一键授权所有权限（顺序：无障碍 → 存储 → 录屏 → 相册 → 摄像头/麦克风）
     */
    private fun grantAllPermissions() {
        when {
            !cachedAccessibilityEnabled -> requestAccessibilityPermission()
            !cachedStorageGranted -> requestStoragePermission()
            !cachedMediaProjectionAuthorized -> requestMediaProjectionPermission()
            !cachedAlbumGranted -> requestAlbumPermission()
            !cachedCameraGranted || !cachedMicrophoneGranted -> requestCameraMicrophonePermissions()
            else -> {
                Toast.makeText(this, "All permissions are ready", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 显示重置对话框 (长按触发)
     */
    private fun showResetDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Reset permissions")
            .setMessage("Reset all permissions?\n\nThis will:\n• Stop the foreground service\n• Clear screen recording permission\n• Require re-authorization")
            .setPositiveButton("Reset") { _, _ ->
                MediaProjectionHelper.releaseCompletely(this)
                Toast.makeText(this, "Permissions reset", Toast.LENGTH_SHORT).show()
                mainHandler.postDelayed({ checkPermissionsAsync() }, 500)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * 读取系统设置里的无障碍开关状态。
     * 注意：从“无权限 -> 有权限”时，系统设置通常比 service 真正连上更早完成，
     * 所以最终 UI 判定不要只看这里。
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        return try {
            val accessibilityEnabled = Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1

            val serviceShort = "${packageName}/.accessibility.service.PhoneAccessibilityService"
            val serviceFull = "${packageName}/com.shijing.xomniclaw.accessibility.service.PhoneAccessibilityService"
            val enabledServices = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )

            val serviceEnabled = enabledServices?.let {
                it.contains(serviceShort) || it.contains(serviceFull)
            } ?: false

            accessibilityEnabled && serviceEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accessibility service", e)
            false
        }
    }

    /**
     * 最终无障碍“已授权”状态判定：
     * - 只判断系统设置已开 + serviceInstance 已建立
     * - 不再依赖 rootInActiveWindow；那个更适合作为“当前是否可立即抓 UI”的运行态指标
     */
    private suspend fun resolveAccessibilityEnabled(): Boolean {
        val settingsEnabled = isAccessibilityServiceEnabled()
        if (!settingsEnabled) return false

        repeat(8) { attempt ->
            val serviceConnected = AccessibilityBinderService.serviceInstance != null
            if (serviceConnected) {
                if (attempt > 0) {
                    Log.d(TAG, "Accessibility service connected after ${attempt + 1} checks")
                }
                return true
            }
            delay(250)
        }

        Log.w(TAG, "Accessibility settings enabled, but serviceInstance is still null")
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_CODE_CAMERA_MIC -> {
                val allGranted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    Toast.makeText(this, "✅ Camera and microphone permissions granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ Some permissions were not granted; you can enable them manually in system settings", Toast.LENGTH_LONG).show()
                }
                mainHandler.postDelayed({ checkPermissionsAsync("onRequestPermissionsResult-cameraMic") }, 400)
            }
            REQUEST_CODE_ALBUM -> {
                val allGranted = grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    Toast.makeText(this, "✅ Gallery permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ Gallery permission not granted; you can enable it manually in system settings", Toast.LENGTH_LONG).show()
                }
                mainHandler.postDelayed({ checkPermissionsAsync("onRequestPermissionsResult-album") }, 400)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode")

        when (requestCode) {
            REQUEST_CODE_MEDIA_PROJECTION -> {
                val granted = MediaProjectionHelper.handlePermissionResult(this, requestCode, resultCode, data)

                if (granted) {
                    Toast.makeText(this, "✅ Screen recording permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ Screen recording permission denied", Toast.LENGTH_SHORT).show()
                }

                mainHandler.postDelayed({ checkPermissionsAsync("onActivityResult-mediaProjection") }, 500)
            }

            REQUEST_CODE_ACCESSIBILITY -> {
                Toast.makeText(this, "Checking accessibility service status...", Toast.LENGTH_SHORT).show()
                mainHandler.postDelayed({ checkPermissionsAsync("onActivityResult-accessibility") }, 1000)
            }

            REQUEST_CODE_MANAGE_STORAGE -> {
                val granted = isStoragePermissionGranted()
                if (granted) {
                    Toast.makeText(this, "✅ Storage permission granted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "❌ Storage permission denied", Toast.LENGTH_SHORT).show()
                }
                mainHandler.postDelayed({ checkPermissionsAsync("onActivityResult-storage") }, 500)
            }
        }
    }
}
