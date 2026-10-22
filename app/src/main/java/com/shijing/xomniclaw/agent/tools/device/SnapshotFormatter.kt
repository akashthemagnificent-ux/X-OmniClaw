package com.shijing.xomniclaw.agent.tools.device

/**
 * OmniClaw Source Reference:
 * - ../xomniclaw/src/agents/tools/browser/(all)
 *
 * OmniClaw adaptation: format accessibility tree output aligned with
 * Playwright's snapshot format (aria refs).
 */

import kotlin.math.max

object SnapshotFormatter {

    /** Matches CapCut-style duration overlay on video thumbs, e.g. 00:22, 1:05 */
    private val DURATION_LIKE_TEXT = Regex("^\\d{1,2}:\\d{2}(\\.\\d+)?$")

    /** Status bar clock / small overlays also look like mm:ss — ignore top/bottom strips for hints & banner. */
    private fun contentBandY(screenHeight: Int): IntRange {
        val top = max(160, (screenHeight * 0.075f).toInt())
        val bottom = screenHeight - max(80, (screenHeight * 0.05f).toInt())
        return top..bottom
    }

    private fun nodeCenterYInContentBand(n: RefNode, screenHeight: Int): Boolean {
        val cy = n.bounds.centerY()
        return cy in contentBandY(screenHeight)
    }

    private fun textLooksLikeDuration(t: String): Boolean = t.trim().matches(DURATION_LIKE_TEXT)

    /** CapCut grid checkbox link: empty text, or a selection ordinal like '1'/'2'… */
    private val CAPCUT_GRID_LINK_ORDINAL = Regex("^\\d{1,2}$")

    private fun isCapCutGridCheckboxLink(node: RefNode): Boolean {
        if (node.role != "link") return false
        val t = node.text?.trim().orEmpty()
        return t.isEmpty() || t.matches(CAPCUT_GRID_LINK_ORDINAL)
    }

    /** In flat traversal order, the link ref of each (textless button + checkbox link) pair (left→right ≈ cell order). */
    private fun collectCapCutPairCheckboxLinkRefs(nodes: List<RefNode>): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < nodes.size - 1) {
            val a = nodes[i]
            val b = nodes[i + 1]
            if (a.role == "button" && a.text.isNullOrBlank() && isCapCutGridCheckboxLink(b)) {
                out.add(b.ref)
                i += 2
            } else {
                i++
            }
        }
        return out
    }

    /**
     * Theme album / change source: tap the **link "照片视频" (Photos & videos)**; never the
     * textless button above it, which RefManager may mistake for a thumbnail and "correct" by coordinates.
     */
    fun capCutAlbumSourceDropdownHint(nodes: List<RefNode>): String? {
        val n = nodes.firstOrNull { node ->
            node.packageName == "com.lemon.lv" &&
                node.role == "link" &&
                node.clickable &&
                node.text?.contains("照片视频") == true
        } ?: return null
        val label = n.text?.trim().orEmpty().ifEmpty { "照片视频" }
        return "[CapCut·album source] To pick an album such as **`A_latest`**, **tap** the **link '$label' [ref=${n.ref}]** (ref **as of the current snapshot**). " +
            "**Never** substitute the nearby **textless `button`** (often **e5** in the tree): the engine treats it as a large thumbnail, applies **coordinate correction**, and mis-taps the checkbox of the cell below."
    }

    /**
     * Each row in the album dropdown is usually `button` (whole row) -> `text '<album>'` -> `text '<count>'`.
     * Models tend to take the **`button` of the next row** as part of A_latest — it actually belongs to the next album.
     */
    fun capCutA_latestAlbumListRowHint(nodes: List<RefNode>): String? {
        val pkg = "com.lemon.lv"
        val idx = nodes.indexOfFirst { n ->
            n.packageName == pkg && n.text?.trim() == "A_latest"
        }
        if (idx <= 0) return null
        val rowButton = nodes[idx - 1]
        if (rowButton.packageName != pkg || rowButton.role != "button" || !rowButton.clickable) return null
        val nameNode = nodes[idx]
        var nextRowWarn = ""
        if (idx + 2 < nodes.size) {
            val nextBtn = nodes[idx + 2]
            if (nextBtn.role == "button" && nextBtn.clickable && nextBtn.packageName == pkg) {
                val nextAlbumName = nodes.getOrNull(idx + 3)?.text?.trim().orEmpty()
                nextRowWarn = " **Do not tap** the **`button [ref=${nextBtn.ref}]`** that follows the count text (the whole next row" +
                    (if (nextAlbumName.isNotEmpty()) ", roughly **$nextAlbumName**" else "") + ")."
            }
        }
        return "[CapCut·pick the A_latest row] Find **`text 'A_latest' [ref=${nameNode.ref}]`** in the list and **tap that row's `button [ref=${rowButton.ref}]`** (the node **before** the album name; it is the full-row hit area). $nextRowWarn"
    }

    /**
     * When many **content-area** nodes expose mm:ss (video thumb overlays), the picker is likely video-heavy.
     * Excludes the status-bar clock (e.g. 19:22) and edge UI so the CapCut home screen does not trigger this banner.
     */
    fun mediaPickerBannerIfNeeded(nodes: List<RefNode>, screenHeight: Int): String? {
        val durCount = nodes.count { n ->
            val t = n.text?.trim() ?: return@count false
            textLooksLikeDuration(t) && nodeCenterYInContentBand(n, screenHeight)
        }
        if (durCount < 2) return null
        return "[Media grid] $durCount visible \"m:ss\" labels (e.g. 00:22) here, so most cells are video thumbnails. " +
            "If the task needs still photos, tap the \"照片\" (Photos) tab at the top before selecting. " +
            "checked/selected on a node means it is already selected; tapping the same checkbox again usually deselects. " +
            "Do not tap the large textless button (it is the whole thumbnail and opens a preview); tap only the small circle at each cell's top-right (a link in current CapCut, possibly with selected/ordinal). " +
            "After each tap, snapshot and check whether N in \"下一步 (N)\" (Next (N)) increased."
    }

    /**
     * When the m:ss banner is showing and the "视频" (Videos) tab is still active, stress that every cell is a video, so the .jpg order from `list_gallery_images` is not applied blindly.
     */
    fun capCutVideoTabSelectedPhotoReminder(nodes: List<RefNode>, screenHeight: Int): String? {
        if (mediaPickerBannerIfNeeded(nodes, screenHeight) == null) return null
        if (!nodes.any { it.text?.trim() == "视频" && it.selected }) return null
        val photoRef = nodes.asSequence()
            .filter { it.text?.trim() == "照片" && it.clickable }
            .minWithOrNull(
                compareBy<RefNode> { if (it.role == "button") 0 else 1 }.thenBy { it.ref }
            )?.ref ?: return null
        return "[CapCut·still-photo prerequisite] The **视频** (Videos) tab is active, so the grid is mostly **video** thumbnails. If the task needs **photos** (e.g. today's shots, `list_gallery_images` returned .jpg): **do not** follow the cell-order list below — that selects videos. **First** `tap` the **照片** (Photos) tab at the top (e.g. **ref=$photoRef**), **then snapshot** to confirm **照片 is selected** before ticking cells."
    }

    /**
     * CapCut shows "选择3个以上素材…" (pick 3+ items) at the bottom; models then think 3 is the target, which contradicts K from `list_gallery_images`.
     */
    fun capCutThreePlusSuggestionReminder(nodes: List<RefNode>): String? {
        val hit = nodes.any { n ->
            val t = n.text?.trim().orEmpty()
            t.contains("3个以上") && (t.contains("素材") || t.contains("成片"))
        }
        if (!hit) return null
        return "[CapCut·target count] The on-screen \"选择3个以上…\" is only a **3-or-more recommendation**, **not** the completion condition. " +
            "If `list_gallery_images` was called, the target is **K = the number of items it returned** (Skill §1): keep ticking (scroll and snapshot again as needed) until **N in `下一步 (N)` equals K**, then tap Next; " +
            "**never** tap Next while **N < K** just because 3 or more are selected."
    }

    /**
     * The CapCut album grid usually alternates a textless button (whole cell) and a link (checkbox).
     * The checkbox is usually a link, but **ref numbers carry no parity rule** and are renumbered on
     * every snapshot — read the tree or the cell-order line.
     */
    fun capCutGridPairingHint(nodes: List<RefNode>): String? {
        if (nodes.size < 6) return null
        val linkRefs = collectCapCutPairCheckboxLinkRefs(nodes)
        if (linkRefs.size < 4) return null
        return "[CapCut picker] Cells come in pairs: a large textless button plus a small circle (usually a link). Item N = the circle ref of the **Nth cell from the left** (see the cell-order line below). " +
            "**Parity of the number after \"e\" says nothing about link vs button**, and it changes every snapshot — never treat the large button as the checkbox and never guess the ref pattern. " +
            "A \"1\" on a circle means **that cell** is selected, **not the whole row**; empty circles in the same row are still unselected, and item 2 is usually **the cell to the right of the selected one**."
    }

    /**
     * List the checkbox link refs of the first cells so the model does not tap cell 5 when asked for item 2 (e.g. e25 instead of e19).
     */
    fun capCutGridOrderedLinkRefsLine(nodes: List<RefNode>): String? {
        val list = collectCapCutPairCheckboxLinkRefs(nodes)
        if (list.size < 4) return null
        val head = list.take(8).joinToString("、")
        val ellipsis = if (list.size > 8) "…" else ""
        val second = list[1]
        val antiConfuse = if (list.size >= 5) {
            "For item 2 use **$second**, not **${list[4]}** (cell 5)."
        } else {
            "Item M = the Mth ref above."
        }
        return "[CapCut picker·cell order] Checkbox links left to right: $head$ellipsis. $antiConfuse"
    }

    private fun formatVisibleText(node: RefNode, screenHeight: Int): String {
        val text = node.text
        if (text.isNullOrBlank()) return ""
        val t = text.trim().take(100)
        val hint = if (textLooksLikeDuration(t) && nodeCenterYInContentBand(node, screenHeight)) {
            " [≈video duration]"
        } else {
            ""
        }
        return " '$t'$hint"
    }

    /**
     * Compact format (default) — aligned with Playwright ai snapshot.
     * Shows role, text, ref, and interactive flags.
     */
    fun compact(nodes: List<RefNode>, screenWidth: Int, screenHeight: Int, appName: String?): String {
        val sb = StringBuilder()
        sb.appendLine("[Screen: ${screenWidth}x${screenHeight}${appName?.let { " $it" } ?: ""}]")
        sb.appendLine()

        for (node in nodes) {
            val indent = "  ".repeat(node.depth.coerceAtMost(6))
            val flags = buildList {
                if (node.clickable) add("clickable")
                if (node.editable) add("editable")
                if (node.scrollable) add("scrollable")
                if (node.focusable && node.editable) { /* already shown as editable */ }
                else if (node.focusable) add("focusable")
                if (node.checkable) add("checkable")
                if (node.checked) add("checked")
                if (node.selected) add("selected")
            }
            val flagStr = if (flags.isNotEmpty()) " (${flags.joinToString(", ")})" else ""
            val textStr = formatVisibleText(node, screenHeight)

            sb.appendLine("$indent${node.role}$textStr [ref=${node.ref}]$flagStr")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Interactive format — only interactive elements, with coordinates.
     * Best for quick action selection.
     */
    fun interactive(nodes: List<RefNode>, appName: String?, screenHeight: Int): String {
        val sb = StringBuilder()
        sb.appendLine("[Screen: ${appName ?: "Android"}] Interactive elements:")
        sb.appendLine()

        val interactiveNodes = nodes.filter { it.clickable || it.editable || it.scrollable }
        for (node in interactiveNodes) {
            val textStr = formatVisibleText(node, screenHeight)
            val cx = node.bounds.centerX()
            val cy = node.bounds.centerY()
            val state = buildList {
                if (node.checkable) add("checkable")
                if (node.checked) add("checked")
                if (node.selected) add("selected")
            }.joinToString(",").let { if (it.isNotEmpty()) " [$it]" else "" }
            sb.appendLine("[${node.ref}] ${node.role}$textStr$state ($cx, $cy)")
        }

        if (interactiveNodes.isEmpty()) {
            sb.appendLine("(no interactive elements found)")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Tree format — full hierarchy with bounds.
     */
    fun tree(nodes: List<RefNode>, screenWidth: Int, screenHeight: Int, appName: String?): String {
        val sb = StringBuilder()
        sb.appendLine("[Screen: ${screenWidth}x${screenHeight}${appName?.let { " $it" } ?: ""}]")
        sb.appendLine()

        for (node in nodes) {
            val indent = "│  ".repeat(node.depth.coerceAtMost(6))
            val prefix = if (node.depth > 0) "├─ " else ""
            val b = node.bounds
            val flags = buildList {
                if (node.clickable) add("clickable")
                if (node.editable) add("editable")
                if (node.scrollable) add("scrollable")
                if (node.checkable) add("checkable")
                if (node.checked) add("checked")
                if (node.selected) add("selected")
            }.joinToString(" ")
            val textStr = formatVisibleText(node, screenHeight)

            sb.appendLine("$indent$prefix${node.role}$textStr [ref=${node.ref}] bounds=(${b.left},${b.top},${b.right},${b.bottom}) $flags".trimEnd())
        }

        return sb.toString().trimEnd()
    }
}
