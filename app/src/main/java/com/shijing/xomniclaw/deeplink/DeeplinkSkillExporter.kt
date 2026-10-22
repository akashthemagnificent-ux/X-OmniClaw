package com.shijing.xomniclaw.deeplink

import com.shijing.xomniclaw.deeplink.model.DeeplinkBookmark
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 将 Deeplink 收藏导出为 Skill。
 *
 * 生成的 SKILL.md 格式与 clipboard-to-shortcut 保持一致，
 * 用户说出 Skill 名称即可一键直达对应页面。
 */
object DeeplinkSkillExporter {
    private const val WORKSPACE_SKILLS_DIR = "/sdcard/.xomniclaw/workspace/skills"
    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    data class ExportResult(
        val success: Boolean,
        val skillName: String,
        val skillDir: File? = null,
        val skillFile: File? = null,
        val message: String
    )

    /**
     * App 包名 -> (emoji, 中文名称) 映射
     */
    private val appMapping = mapOf(
        "com.sankuai.meituan" to ("🍜" to "Meituan"),
        "com.taobao.taobao" to ("🛒" to "Taobao"),
        "com.xunmeng.pinduoduo" to ("🍊" to "Pinduoduo"),
        "com.ss.android.ugc.aweme" to ("🎵" to "Douyin"),
        "com.smile.gifmaker" to ("🎬" to "Kuaishou"),
        "com.zhihu.android" to ("💡" to "Zhihu"),
        "com.xingin.xhs" to ("📕" to "Xiaohongshu"),
        "tv.danmaku.bili" to ("📺" to "Bilibili"),
        "com.autonavi.minimap" to ("🗺️" to "Amap"),
        "com.tencent.qqmusic" to ("🎶" to "QQ Music"),
        "com.eg.android.AlipayGphone" to ("💰" to "Alipay"),
        "com.baidu.searchbox" to ("🔍" to "Baidu"),
        "com.dianping.v1" to ("🍜" to "Dianping"),
        "com.jingdong.app.mall" to ("🛍️" to "JD"),
        "com.tencent.mm" to ("💬" to "WeChat"),
        "com.alibaba.android.rimet" to ("💼" to "DingTalk"),
        "com.ss.android.lark" to ("🐦" to "Feishu")
    )

    /**
     * 根据 DeeplinkBookmark 生成一个不超过10字的 Skill 名称。
     *
     * 策略：
     * 1. 优先使用 pageTitle（截取前面有意义的部分）
     * 2. 如果 pageTitle 太短或无意义，使用 appName + 页面类型
     * 3. 最终限制在10个字符内
     */
    fun generateSkillName(bookmark: DeeplinkBookmark): String {
        val appName = appMapping[bookmark.packageName]?.second ?: bookmark.appName
        val pageTitle = bookmark.pageTitle.trim()

        // 尝试从 pageTitle 提取有意义的名称
        val cleanTitle = pageTitle
            .replace(Regex("^(美团|淘宝|拼多多|抖音|快手|知乎|小红书|哔哩哔哩|高德|QQ音乐|支付宝|百度|大众点评|京东|微信|钉钉|飞书)[-_\\s]*"), "")
            .replace(Regex("[-_|｜·].*$"), "") // 移除后缀分隔符后的内容
            .trim()

        // 从 dataUri 中提取页面类型信息
        val pageType = extractPageTypeFromUri(bookmark.dataUri)

        val skillName = when {
            cleanTitle.length in 2..8 -> {
                // pageTitle 有意义且长度合适
                if (cleanTitle.contains(appName)) cleanTitle else "$appName$cleanTitle"
            }
            pageType.isNotBlank() -> {
                // 从 URI 中提取的页面类型
                "$appName$pageType"
            }
            cleanTitle.isNotBlank() -> {
                // pageTitle 太长，截取前面部分
                val prefix = cleanTitle.take(6)
                if (prefix.contains(appName)) prefix else "$appName${prefix.take(4)}"
            }
            else -> {
                // 兜底：使用 appName + 页面
                "${appName} page"
            }
        }

        // 10 was tuned for CJK; English app names need more room before truncating
        return skillName.take(24)
    }

    /**
     * 从 dataUri 中提取页面类型关键词
     */
    private fun extractPageTypeFromUri(dataUri: String): String {
        if (dataUri.isBlank()) return ""

        // 美团相关
        if (dataUri.contains("meituan")) {
            return when {
                dataUri.contains("seckill", ignoreCase = true) || 
                    dataUri.contains("SECOND_KILL") -> "flash sale"
                dataUri.contains("deal-detail") -> "group buy"
                dataUri.contains("food", ignoreCase = true) -> "food"
                dataUri.contains("hotel", ignoreCase = true) -> "hotel"
                dataUri.contains("movie", ignoreCase = true) -> "movie"
                dataUri.contains("shop", ignoreCase = true) -> "merchant"
                else -> ""
            }
        }

        // 淘宝相关
        if (dataUri.contains("taobao") || dataUri.contains("tb.cn")) {
            return when {
                dataUri.contains("search") -> "search"
                dataUri.contains("detail") -> "product"
                dataUri.contains("shop") -> "store"
                dataUri.contains("cart") -> "cart"
                else -> ""
            }
        }

        // 抖音相关
        if (dataUri.contains("douyin")) {
            return when {
                dataUri.contains("video") -> "video"
                dataUri.contains("live") -> "live"
                dataUri.contains("user") -> "home page"
                else -> ""
            }
        }

        return ""
    }

    /**
     * 将 DeeplinkBookmark 导出为 Skill
     */
    fun export(bookmark: DeeplinkBookmark, customName: String? = null): ExportResult {
        val skillName = customName?.take(10) ?: generateSkillName(bookmark)

        // 检查 deeplink 是否有效
        if (bookmark.dataUri.isBlank() && bookmark.intentCommand.isBlank()) {
            return ExportResult(
                success = false,
                skillName = skillName,
                message = "This bookmark has no valid Deeplink and cannot be exported as a Skill"
            )
        }

        val skillDir = File(WORKSPACE_SKILLS_DIR, skillName)
        val skillFile = File(skillDir, "SKILL.md")

        return try {
            if (!skillDir.exists()) {
                skillDir.mkdirs()
            }
            val content = buildSkillMarkdown(bookmark, skillName)
            skillFile.writeText(content)

            ExportResult(
                success = true,
                skillName = skillName,
                skillDir = skillDir,
                skillFile = skillFile,
                message = "Created shortcut \"$skillName\""
            )
        } catch (e: Exception) {
            ExportResult(
                success = false,
                skillName = skillName,
                message = "Export failed: ${e.message}"
            )
        }
    }

    /**
     * 检查 Skill 名称是否已存在
     */
    fun skillExists(skillName: String): Boolean {
        val skillDir = File(WORKSPACE_SKILLS_DIR, skillName)
        return skillDir.exists() && File(skillDir, "SKILL.md").exists()
    }

    /**
     * 构建符合 clipboard-to-shortcut 格式的 SKILL.md 内容
     */
    private fun buildSkillMarkdown(bookmark: DeeplinkBookmark, skillName: String): String {
        val (emoji, appDisplayName) = appMapping[bookmark.packageName] ?: ("🔗" to bookmark.appName)
        val packageName = bookmark.packageName
        val hasDeeplink = bookmark.dataUri.isNotBlank()
        val uri = bookmark.dataUri.ifBlank { "" }
        val amCommand = bookmark.intentCommand.ifBlank { bookmark.effectiveAmCommand }
        val createdDate = dateFormatter.format(Date())
        val pageDesc = bookmark.pageTitle.ifBlank { bookmark.shortActivityName }

        return buildString {
            // YAML Frontmatter
            appendLine("---")
            appendLine("name: $skillName")
            appendLine("description: |")
            appendLine("  Shortcut that jumps straight to a specific ${appDisplayName} page. Auto-created from a Deeplink bookmark.")
            appendLine("  Activated when the user says \"open $skillName\", \"use $skillName\", or just \"$skillName\".")
            appendLine("metadata:")
            appendLine("  {")
            appendLine("    \"xomniclaw\": {")
            appendLine("      \"always\": false,")
            appendLine("      \"emoji\": \"$emoji\",")
            appendLine("      \"version\": \"1.0.0\",")
            appendLine("      \"category\": \"navigation\"")
            appendLine("    }")
            appendLine("  }")
            appendLine("---")
            appendLine()

            // 标题
            appendLine("# $skillName")
            appendLine()
            appendLine("Jump straight to the \"$pageDesc\" page in ${appDisplayName}.")
            appendLine()

            // 跳转方式说明
            if (hasDeeplink) {
                appendLine("## 🚫 Absolutely forbidden (violating this always fails / crashes)")
                appendLine()
                appendLine("> **Ban 1: exec / shell (including `am start`)** — throws SecurityException 100% of the time.")
                appendLine(">")
                appendLine("> **Ban 2: `start_activity` (any parameter combination)** — the most common failure mode for this kind of skill.")
                appendLine("> The tool cannot carry the deeplink's query parameters (such as `mrn_biz` / `mrn_entry` / `mrn_component` / `poiidEncrypt`),")
                appendLine("> so even if `${bookmark.activityName}` launches, it is only a bare shell of that Activity —")
                appendLine("> with no business parameters it falls back to the ${appDisplayName} default/home page: **it looks like the jump worked, but you never reached the target page**.")
                appendLine(">")
                appendLine("> | Operation | ✅ Must use | ❌ Absolutely forbidden |")
                appendLine("> |---|---|---|")
                appendLine("> | Jump to this shortcut's target page | `device(action=\"open\", uri=\"<full deeplink>\", package_name=\"$packageName\")` | `start_activity(component=...)` / `start_activity(package=…, activity=…)` / `start_activity(uri=…)` / `am start …` |")
                appendLine(">")
                appendLine("> **✅ The only correct way (package_name + full uri required):**")
                appendLine("> ```kotlin")
                appendLine("> device(action = \"open\", uri = \"<full deeplink including every ?key=val&… query parameter>\", package_name = \"$packageName\")")
                appendLine("> ```")
                appendLine(">")
                appendLine("> **think → action consistency self-check (re-read before every function call emitted)**:")
                appendLine("> 1. Even if `<think>` said to use `device(action=\\\"open\\\")`, the actual function call must be `device` — **never switch to `start_activity`**.")
                appendLine("> 2. The uri field must **copy verbatim** the full string from the \"Deeplink details\" section below (including all `?key=val&…` query parameters);")
                appendLine(">    do not omit a single parameter — losing any one of them falls back to the ${appDisplayName} home page.")
            } else {
                appendLine("## ⚠️ Root permission required")
                appendLine()
                appendLine("> This page has no Deeplink, so the target Activity must be launched through a **root shell**.")
                appendLine("> Make sure the device has root.")
                appendLine(">")
                appendLine("> **✅ How to jump to the target page (use_root=true):**")
                appendLine("> ```kotlin")
                appendLine("> device(action = \"open\", package_name = \"$packageName\", class_name = \"${bookmark.activityName}\", use_root = true)")
                appendLine("> ```")
            }
            appendLine()

            // 执行步骤
            appendLine("## 📋 Execution steps")
            appendLine()
            appendLine("### Step 1: Jump to the target page")
            appendLine()

            if (hasDeeplink) {
                appendLine("```kotlin")
                appendLine("device(action = \"open\", uri = \"$uri\", package_name = \"$packageName\")")
                appendLine("```")
            } else {
                appendLine("```kotlin")
                appendLine("// This page has no Deeplink; launch the target Activity through a root shell")
                appendLine("device(action = \"open\", package_name = \"$packageName\", class_name = \"${bookmark.activityName}\", use_root = true)")
                appendLine("```")
            }
            appendLine()

            appendLine("### Step 2: Finish the task")
            appendLine()
            appendLine("Once the jump completes, **reply to the user directly**:")
            appendLine()
            appendLine("```")
            appendLine("Jumped to the \"${pageDesc}\" page in ${appDisplayName}.")
            appendLine("```")
            appendLine()
            appendLine("> **⚠️ Important: do not run snapshot or any follow-up action — the task ends here.**")
            appendLine()

            // 注意事项
            appendLine("## ⚠️ Notes")
            appendLine()
            if (hasDeeplink) {
                appendLine("- Use `device(action=\"open\", uri=\"...\", package_name=\"$packageName\")`; without package_name it opens the browser")
                appendLine("- ⛔ The `Activity: ${bookmark.activityName}` recorded below is **for audit provenance only**, not an entry point for the model;")
                appendLine("  never call `start_activity(component=…/${bookmark.activityName.substringAfterLast('.')})` from that line — it launches the Activity with no parameters and falls back to the ${appDisplayName} home page.")
            } else {
                appendLine("- This page has no Deeplink; use `device(action=\"open\", ..., use_root=true)` to jump through a root shell")
                appendLine("- The device must have root, otherwise the Activity cannot be launched")
            }
            appendLine("- Original source: ${bookmark.sourceActionSummary.ifBlank { "Deeplink bookmark export" }}")
            appendLine("- Original page: ${bookmark.appName} / ${bookmark.displayName}")
            appendLine("- Activity：${bookmark.activityName}")
            appendLine("- Created at: $createdDate")

            if (hasDeeplink) {
                appendLine()
                appendLine("### Deeplink details")
                appendLine()
                appendLine("```")
                appendLine(uri)
                appendLine("```")
            }
        }
    }
}
