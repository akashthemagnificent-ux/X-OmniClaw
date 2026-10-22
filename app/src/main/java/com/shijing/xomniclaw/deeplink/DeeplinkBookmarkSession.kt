package com.shijing.xomniclaw.deeplink

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.shijing.xomniclaw.accessibility.service.AccessibilityBinderService
import com.shijing.xomniclaw.accessibility.service.AccessibilityEventDispatcher
import com.shijing.xomniclaw.deeplink.model.DeeplinkBookmark
import com.shijing.xomniclaw.deeplink.model.TrackedPage

/**
 * 录制期间的 deeplink 收藏会话。
 *
 * 它只关心两件事：
 * 1. 当前录制过程中“用户走到了哪个页面”
 * 2. 用户点击收藏按钮时，如何把当前页落成一条可回放记录
 */
object DeeplinkBookmarkSession {
    data class SessionState(
        val rootAvailable: Boolean = false,
        val currentPage: TrackedPage? = null
    )

    data class BookmarkResult(
        val success: Boolean,
        val message: String,
        val bookmark: DeeplinkBookmark? = null
    )

    @Volatile
    private var sessionState: SessionState = SessionState()

    fun start(context: Context): SessionState {
        val rootAvailable = RootShellExecutor.hasRootAccess(forceRefresh = true)
        val service = AccessibilityBinderService.serviceInstance
        val trackedPage = if (service != null &&
            service.currentPackageName.isNotBlank() &&
            service.activityClassName.isNotBlank()
        ) {
            buildTrackedPage(
                context = context,
                packageName = service.currentPackageName,
                activityName = service.activityClassName,
                windowTitle = ""
            )
        } else {
            null
        }
        sessionState = SessionState(
            rootAvailable = rootAvailable,
            currentPage = trackedPage
        )
        return sessionState
    }

    fun stop() {
        sessionState = SessionState()
    }

    fun snapshot(): SessionState = sessionState

    fun onAccessibilityEvent(
        context: Context,
        event: AccessibilityEventDispatcher.RecordedAccessibilityEvent
    ): SessionState {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return sessionState
        }
        if (event.packageName.isBlank() || event.className.isBlank()) {
            return sessionState
        }
        val trackedPage = buildTrackedPage(
            context = context,
            packageName = event.packageName,
            activityName = event.className,
            windowTitle = event.text
        )
        sessionState = sessionState.copy(currentPage = trackedPage)
        return sessionState
    }

    fun bookmarkCurrentPage(context: Context): BookmarkResult {
        val state = sessionState
        if (!state.rootAvailable) {
            return BookmarkResult(
                success = false,
                message = "This device has no root, so a deeplink bookmark cannot be created"
            )
        }
        val page = state.currentPage ?: return BookmarkResult(
            success = false,
            message = "The current page has not been tracked yet; switch pages once first"
        )

        val capturedSpec = runCatching {
            DeeplinkIntentCapture.capture(page.packageName, page.activityName)
        }.getOrNull()

        val bookmark = DeeplinkBookmark(
            packageName = page.packageName,
            activityName = page.activityName,
            appName = page.appName,
            pageTitle = page.displayTitle,
            dataUri = capturedSpec?.dataUri.orEmpty(),
            intentCommand = capturedSpec?.amCommand.orEmpty(),
            rawIntentLine = capturedSpec?.rawIntentLine.orEmpty(),
            capturedExtrasCount = capturedSpec?.extras?.size ?: 0,
            sourceActionSummary = "Manually saved during the recording session"
        )
        DeeplinkBookmarkStore.add(bookmark)

        // 自动导出为 Skill
        val exportResult = DeeplinkSkillExporter.export(bookmark)

        val message = if (exportResult.success) {
            "Saved and created shortcut \"${exportResult.skillName}\""
        } else if (bookmark.dataUri.isNotBlank()) {
            "Saved the current page of ${page.appName} and captured a deeplink (skill export failed)"
        } else if (bookmark.hasPreciseJumpSpec) {
            "Saved the current page of ${page.appName} with replayable parameters"
        } else {
            "Saved the current page of ${page.appName}, falling back to the Activity"
        }
        return BookmarkResult(
            success = true,
            message = message,
            bookmark = bookmark
        )
    }

    private fun buildTrackedPage(
        context: Context,
        packageName: String,
        activityName: String,
        windowTitle: String
    ): TrackedPage {
        val appName = try {
            val applicationInfo = context.packageManager.getApplicationInfo(packageName, 0)
            context.packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast(".")
        }
        return TrackedPage(
            packageName = packageName,
            activityName = activityName,
            appName = appName,
            windowTitle = windowTitle
        )
    }
}
