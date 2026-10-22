package com.shijing.xomniclaw.agent.memory.evolution

import java.util.Locale

/**
 * 全局记忆进化的纯策略函数，便于单元测试，避免关键过滤逻辑散落在管理器中。
 */
object MemoryEvolutionPolicy {
    fun normalizeForDedupe(text: String): String {
        return text.lowercase(Locale.getDefault())
            .replace(Regex("\\s+"), "")
            .take(120)
    }

    fun isSensitive(text: String): Boolean {
        val sensitiveTerms = listOf("密码", "验证码", "token", "api key", "apikey", "secret", "身份证", "银行卡")
        return sensitiveTerms.any { text.contains(it, ignoreCase = true) }
    }

    fun compactGlobalMemory(content: String, maxChars: Int): String {
        if (content.length <= maxChars) {
            return content
        }
        val output = mutableListOf<String>()
        val sectionItems = linkedMapOf<String, MutableList<String>>()
        var currentSection: String? = null
        content.lines().forEach { line ->
            if (line.startsWith("## ")) {
                currentSection = line
                sectionItems.getOrPut(line) { mutableListOf() }
            } else if (line.startsWith("- ") && currentSection != null && !line.contains("No stable memory yet")) {
                sectionItems.getOrPut(currentSection!!) { mutableListOf() }.add(line)
            }
        }

        val requiredSkeleton = buildString {
            appendLine("# X-OmniClaw global memory")
            appendLine()
            appendLine("This file was compressed to budget; only the most recent and most stable task memories are kept.")
            MemoryCategory.values().forEach { category ->
                appendLine()
                appendLine(category.sectionTitle)
                appendLine("- No stable memory yet.")
            }
        }.trim()
        if (requiredSkeleton.length >= maxChars) {
            return requiredSkeleton.take(maxChars)
        }

        output += "# X-OmniClaw global memory"
        output += ""
        output += "This file was compressed to budget; only the most recent and most stable task memories are kept."
        val categories = MemoryCategory.values().toList()
        categories.forEachIndexed { index, category ->
            output += ""
            output += category.sectionTitle
            val kept = sectionItems[category.sectionTitle].orEmpty().takeLast(18)
            var wroteItem = false
            kept.forEach { item ->
                val futureSkeleton = categories.drop(index + 1).joinToString("\n") { nextCategory ->
                    "\n${nextCategory.sectionTitle}\n- No stable memory yet."
                }
                val candidate = (output + item).joinToString("\n")
                if (candidate.length + futureSkeleton.length <= maxChars) {
                    output += item
                    wroteItem = true
                }
            }
            if (!wroteItem) {
                output += "- No stable memory yet."
            }
        }
        return output.joinToString("\n").take(maxChars)
    }
}
