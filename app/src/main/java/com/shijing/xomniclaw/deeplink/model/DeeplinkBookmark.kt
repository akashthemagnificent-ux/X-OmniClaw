package com.shijing.xomniclaw.deeplink.model

/**
 * Deeplink 收藏记录。
 *
 * `dataUri` 是最接近真实 deeplink 的字段；
 * 当某些页面没有直接暴露 data URI 时，仍然保留完整 `am start` 命令作为兜底。
 */
data class DeeplinkBookmark(
    val id: Long = System.currentTimeMillis(),
    val packageName: String,
    val activityName: String,
    val appName: String,
    val pageTitle: String,
    val createdAt: Long = System.currentTimeMillis(),
    val dataUri: String = "",
    val intentCommand: String = "",
    val rawIntentLine: String = "",
    val capturedExtrasCount: Int = 0,
    val sourceActionSummary: String = ""
) {
    val displayName: String
        get() = pageTitle.ifBlank { shortActivityName }

    val shortActivityName: String
        get() {
            val parts = activityName.split(".")
            return if (parts.size > 2) {
                "...${parts.takeLast(2).joinToString(".")}"
            } else {
                activityName
            }
        }

    val hasPreciseJumpSpec: Boolean
        get() = dataUri.isNotBlank() || capturedExtrasCount > 0

    val effectiveAmCommand: String
        get() = intentCommand.ifBlank { "am start -n $packageName/$activityName" }

    val deeplinkSummary: String
        get() = dataUri.ifBlank {
            if (hasPreciseJumpSpec) {
                "Captured launch parameters with extras"
            } else {
                "No explicit deeplink captured; falling back to the Activity"
            }
        }

    val statusText: String
        get() = when {
            dataUri.isNotBlank() && capturedExtrasCount > 0 ->
                "Captured deeplink + $capturedExtrasCount extras"
            dataUri.isNotBlank() ->
                "Captured deeplink; one-tap jump available"
            capturedExtrasCount > 0 ->
                "Captured $capturedExtrasCount extras; exact replay available"
            rawIntentLine.contains("(has extras)") ->
                "Extras detected, but this ROM's output could not be fully parsed"
            else ->
                "Only the Activity fallback was saved"
        }
}
