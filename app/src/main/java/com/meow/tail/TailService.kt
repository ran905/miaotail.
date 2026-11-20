package com.meow.tail

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.7
 *
 * 针对「尝试很多、成功极少」：
 * 1. 写之前先 refresh 节点；失效就重新找，避免在过期节点上 performAction
 * 2. 找节点时优先选「包名与事件来源一致」的那一个，避免抓到百度输入法
 *    自己窗口里的可编辑控件
 * 3. 写失败后 1.5 秒内不重试，避免轮询把「尝试」刷成几百次
 *
 * v1.6
 *
 * 针对荣耀 + 百度输入法：
 * 1. 输入法弹出时「当前活动窗口」可能变成输入法窗口，findFocus / rootInActiveWindow
 *    会指向错误的地方。现在会遍历 windows（需要 flagRetrieveInteractiveWindows），
 *    跨窗口寻找输入框。
 * 2. 不再死要求 isEditable：微信/百度会把编辑框的 isEditable 报成 false。
 *    判定条件放宽为「是 EditText 类名 / 可编辑 / 有焦点且带文本」，三者任一。
 * 3. 事件没有文本时，退回使用 event.getText()。
 * 4. 日志里会打印事件源的类名，方便判断微信到底给的是什么控件。
 */
class TailService : AccessibilityService() {

    companion object {
        const val PREFS = "miao_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_SUFFIX = "suffix"
        const val KEY_TRIGGERS = "triggers"
        const val KEY_ALL_APPS = "all_apps"
        const val KEY_PKGS = "pkg_list"
        const val KEY_ALWAYS = "always_suffix"

        const val DEFAULT_SUFFIX = "喵"
        const val DEFAULT_TRIGGERS = "。！？!?～"
        const val DEFAULT_PKGS = "com.tencent.mm,com.tencent.mobileqq,com.tencent.tim"

        private const val MAX_LOG = 40
        private const val POLL_INTERVAL_MS = 400L
        private const val SETTLE_DELAY_MS = 650L
        private const val IDLE_GATE_MS = 500L
        private const val SELF_WRITE_WINDOW_MS = 400L

        private val logLines = ArrayDeque<String>()
        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        private var lastLogKey = ""
        private var lastLogAt = 0L

        private var eventCount = 0
        private var writeAttempts = 0
        private var writeOk = 0
        private var writeFail = 0

        @Synchronized
        fun snapshotLog(): List<String> = logLines.toList()

        @Synchronized
        fun stats(): String =
            "事件 $eventCount · 尝试 $writeAttempts · 成功 $writeOk · 失败 $writeFail"

        @Synchronized
        fun clearLog() {
            logLines.clear()
            eventCount = 0
            writeAttempts = 0
            writeOk = 0
            writeFail = 0
        }

        @Synchronized
        fun addLog(line: String) {
            val now = System.currentTimeMillis()
            if (line == lastLogKey && now - lastLogAt < 5000) return
            lastLogKey = line
            lastLogAt = now
            val stamp = timeFormat.format(Date())
            logLines.addFirst("$stamp  $line")
            while (logLines.size > MAX_LOG) {
                logLines.removeLast()
            }
        }

        @Synchronized
        fun countEvent() {
            eventCount++
        }

        @Synchronized
        fun countAttempt() {
            writeAttempts++
        }

        @Synchronized
        fun countOk() {
            writeOk++
        }

        @Synchronized
        fun countFail() {
            writeFail++
        }
    }

    private enum class Mode { EVENT, SETTLE, POLL }

    private val handler = Handler(Looper.getMainLooper())
    private val lastSeen = HashMap<String, String>()
    private val lastFailAt = HashMap<String, Long>()
    private var cachedCandidate: AccessibilityNodeInfo? = null
    private var lastChangeAt = 0L
    private var lastSelfWriteAt = 0L
    private var polling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                if (isIdle()) checkFocused("轮询", Mode.POLL)
            } catch (t: Throwable) {
                addLog("轮询异常 ${t.javaClass.simpleName}")
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val settleRunnable = Runnable {
        try {
            if (isIdle()) checkFocused("归位", Mode.SETTLE) else scheduleSettle()
        } catch (t: Throwable) {
            addLog("归位异常 ${t.javaClass.simpleName}")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        addLog("服务已连接")
        if (!polling) {
            polling = true
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            val type = event.eventType
            if (type != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
                type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) {
                return
            }
            if (System.currentTimeMillis() - lastSelfWriteAt < SELF_WRITE_WINDOW_MS) return

            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName) return
            if (!isAllowed(pkg)) return

            lastChangeAt = System.currentTimeMillis()
            countEvent()

            val source = event.source
            addLog("事件 ${typeName(type)} $pkg [${className(source)}]")

            if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                val node = resolveCandidate(source) ?: findCandidateInAnyWindow(pkg)
                if (node != null) {
                    cachedCandidate = node
                    val fromEvent = event.text?.joinToString("")?.takeIf { it.isNotEmpty() }
                    handle(node, pkg, "事件", Mode.EVENT, fromEvent)
                } else {
                    addLog("跳过：没找到输入框 $pkg | ${dumpTree()}")
                }
            }
            scheduleSettle()
        } catch (t: Throwable) {
            addLog("事件异常 ${t.javaClass.simpleName}")
        }
    }

    private fun checkFocused(source: String, mode: Mode) {
        if (System.currentTimeMillis() - lastSelfWriteAt < SELF_WRITE_WINDOW_MS) return
        try {
            val cached = cachedCandidate
            val cachedPkg = try {
                cached?.packageName?.toString()
            } catch (t: Throwable) {
                null
            }
            val node = when {
                cached != null && safeIsCandidate(cached) &&
                    cachedPkg != null && isAllowed(cachedPkg) -> cached
                else -> focusedNode()?.takeIf { n ->
                    runCatching { n.packageName?.toString()?.let { isAllowed(it) } == true }.getOrDefault(false)
                } ?: findCandidateInAnyWindow()
            }
            if (node == null) {
                addLog("跳过：$source 没找到输入框 | ${dumpTree()}")
                return
            }
            cachedCandidate = node
            val pkg = node.packageName?.toString() ?: return
            if (!isAllowed(pkg)) return
            handle(node, pkg, source, mode, null)
        } catch (t: Throwable) {
            addLog("$source 异常 ${t.javaClass.simpleName}")
        }
    }

    private fun handle(
        node: AccessibilityNodeInfo,
        pkg: String,
        source: String,
        mode: Mode,
        textOverride: String?,
    ) {
        if (node.isPassword) return

        val suffix = prefs().getString(KEY_SUFFIX, DEFAULT_SUFFIX).orEmpty()
        val triggers = prefs().getString(KEY_TRIGGERS, DEFAULT_TRIGGERS).orEmpty()
        if (suffix.isEmpty() || triggers.isEmpty()) return

        val text = try {
            node.text?.toString()?.takeIf { it.isNotEmpty() } ?: textOverride
        } catch (t: Throwable) {
            textOverride
        }
        if (text.isNullOrEmpty()) {
            addLog("跳过：文本为空 [${className(node)}] id=${viewId(node)}")
            return
        }

        addLog("候选 [$pkg] ${className(node)} id=${viewId(node)} «${tail(text)}»")

        val previous = lastSeen[pkg]
        lastSeen[pkg] = text

        if (mode == Mode.EVENT && previous == text) return
        if (mode == Mode.POLL && previous != text) return

        val selectionEnd = try {
            node.textSelectionEnd
        } catch (t: Throwable) {
            -1
        }
        if (selectionEnd in 0 until text.length - 1) {
            addLog("跳过：光标在句中")
            return
        }

        val always = prefs().getBoolean(KEY_ALWAYS, true)
        if (mode == Mode.EVENT && triggers.indexOf(text.last()) < 0) return

        val newText = normalize(text, suffix, triggers, always)
        if (newText == null || newText == text) {
            addLog("跳过：无需改动 «${tail(text)}»")
            return
        }

        val now = System.currentTimeMillis()
        if (now - (lastFailAt[pkg] ?: 0L) < 1500L) return

        countAttempt()
        val target = refreshOrFind(node, pkg)
        if (target == null) {
            countFail()
            lastFailAt[pkg] = now
            addLog("写入失败 [$source] 节点已失效且未找到替代")
            return
        }
        if (writeText(target, text, newText)) {
            lastSeen[pkg] = newText
            countOk()
            addLog("补字成功 [$source] $pkg «${tail(newText)}»")
        } else {
            countFail()
            lastFailAt[pkg] = now
            addLog("写入失败 [$source] ${className(target)}")
        }
    }

    private fun normalize(text: String, suffix: String, triggers: String, always: Boolean): String? {
        if (text.isEmpty()) return null

        var end = text.length
        var punctuation: Char? = null
        val lastChar = text[end - 1]
        if (triggers.indexOf(lastChar) >= 0) {
            punctuation = lastChar
            end -= 1
        }

        var core = text.substring(0, end)
        if (!always && punctuation == null) return null

        val index = core.lastIndexOf(suffix)
        if (index >= 0) {
            core = core.removeRange(index, index + suffix.length)
        }
        if (core.isBlank()) return null

        val proposed = core + suffix + (punctuation?.toString() ?: "")
        return if (proposed == text) null else proposed
    }

    private fun writeText(node: AccessibilityNodeInfo, oldText: String, newText: String): Boolean {
        lastSelfWriteAt = System.currentTimeMillis()
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (t: Throwable) {
            // 拿不到焦点也继续尝试写入
        }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText,
            )
        }
        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            moveCaret(node, oldText, newText)
            return true
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText("miao", newText))
        val selectAll = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, oldText.length)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAll)) return false
        if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return false
        return true
    }

    private fun moveCaret(node: AccessibilityNodeInfo, oldText: String, newText: String) {
        val selectionEnd = try {
            node.textSelectionEnd
        } catch (t: Throwable) {
            return
        }
        if (selectionEnd < 0) return
        val target = (selectionEnd + (newText.length - oldText.length)).coerceIn(0, newText.length)
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, target)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, target)
        }
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        } catch (t: Throwable) {
            // 忽略
        }
    }

    private fun focusedNode(): AccessibilityNodeInfo? =
        try {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (t: Throwable) {
            null
        }

    /** 判定是不是「可能的输入框」：三个条件任一即可。 */
    private fun safeIsCandidate(node: AccessibilityNodeInfo): Boolean =
        try {
            isCandidate(node)
        } catch (t: Throwable) {
            false
        }

    private fun isCandidate(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return false
        val cls = node.className?.toString().orEmpty()
        if (node.isEditable) return true
        if (cls.contains("EditText", ignoreCase = true)) return true
        return node.isFocused && node.text != null
    }

    /** 事件源向上找输入框祖先。 */
    private fun resolveCandidate(start: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var node = start
        var depth = 0
        while (node != null && depth < 8) {
            if (safeIsCandidate(node)) return node
            node = try {
                node.parent
            } catch (t: Throwable) {
                null
            }
            depth++
        }
        return null
    }

    /** 节点可能已过期：先 refresh，失败就重新找同包名的输入框。 */
    private fun refreshOrFind(node: AccessibilityNodeInfo, pkg: String): AccessibilityNodeInfo? {
        try {
            if (node.refresh()) return node
        } catch (t: Throwable) {
            // 掉到重新查找
        }
        return findCandidateInAnyWindow(pkg)
    }

    /** 跨窗口搜索：输入法窗口可能抢走「当前活动窗口」。 */
    private fun findCandidateInAnyWindow(preferredPkg: String? = null): AccessibilityNodeInfo? {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            windows.forEach { w ->
                try {
                    w.root?.let { roots.add(it) }
                } catch (t: Throwable) {
                    // 忽略失效窗口
                }
            }
        } catch (t: Throwable) {
            // windows 不可用
        }
        try {
            rootInActiveWindow?.let { roots.add(it) }
        } catch (t: Throwable) {
            // 忽略
        }

        addLog("窗口数 ${roots.size}")

        var allowedFocused: AccessibilityNodeInfo? = null
        var allowed: AccessibilityNodeInfo? = null
        var samePackage: AccessibilityNodeInfo? = null
        var focused: AccessibilityNodeInfo? = null
        var fallback: AccessibilityNodeInfo? = null

        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 600) {
                visited++
                val node = queue.removeFirst()
                try {
                    if (isCandidate(node)) {
                        val nodePkg = node.packageName?.toString()
                        val pkgAllowed = nodePkg != null && isAllowed(nodePkg)
                        if (preferredPkg != null && nodePkg == preferredPkg) {
                            if (node.isFocused) return node
                            if (samePackage == null) samePackage = node
                        }
                        if (pkgAllowed) {
                            if (node.isFocused && allowedFocused == null) allowedFocused = node
                            if (allowed == null) allowed = node
                        }
                        if (focused == null && node.isFocused) focused = node
                        if (fallback == null) fallback = node
                    }
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { queue.add(it) }
                    }
                } catch (t: Throwable) {
                    // 节点失效
                }
            }
        }
        return samePackage ?: allowedFocused ?: allowed ?: focused ?: fallback
    }

    /** 把当前窗口里「有文本或像输入框」的节点摘出来，用于取证。 */
    private fun dumpTree(): String {
        val sb = StringBuilder()
        try {
            val roots = mutableListOf<AccessibilityNodeInfo>()
            try {
                windows.forEach { w ->
                    try {
                        w.root?.let { roots.add(it) }
                    } catch (t: Throwable) {
                        // 忽略
                    }
                }
            } catch (t: Throwable) {
                // windows 不可用
            }
            if (roots.isEmpty()) {
                try {
                    rootInActiveWindow?.let { roots.add(it) }
                } catch (t: Throwable) {
                    // 忽略
                }
            }
            sb.append("窗口数 ").append(roots.size)
            for ((wi, root) in roots.withIndex()) {
                val childCount = try {
                    root.childCount
                } catch (t: Throwable) {
                    -1
                }
                sb.append(" | w").append(wi).append(':').append(className(root)).append('/').append(childCount)
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                var visited = 0
                while (queue.isNotEmpty() && visited < 14) {
                    visited++
                    val node = queue.removeFirst()
                    val t = try {
                        node.text?.toString().orEmpty()
                    } catch (e: Throwable) {
                        ""
                    }
                    if (t.isNotEmpty() || safeIsCandidate(node)) {
                        sb.append(" <").append(className(node))
                        if (t.isNotEmpty()) sb.append(" «").append(t.take(8)).append("»")
                        sb.append('>')
                    }
                    try {
                        for (i in 0 until node.childCount) {
                            node.getChild(i)?.let { queue.add(it) }
                        }
                    } catch (e: Throwable) {
                        // 忽略失效节点
                    }
                }
            }
        } catch (t: Throwable) {
            sb.append("dump异常")
        }
        return sb.toString()
    }

    private fun isIdle(): Boolean = System.currentTimeMillis() - lastChangeAt >= IDLE_GATE_MS

    private fun scheduleSettle() {
        handler.removeCallbacks(settleRunnable)
        handler.postDelayed(settleRunnable, SETTLE_DELAY_MS)
    }

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun isAllowed(pkg: String): Boolean {
        if (pkg == packageName) return false
        val p = prefs()
        if (!p.getBoolean(KEY_ENABLED, true)) return false
        if (p.getBoolean(KEY_ALL_APPS, false)) return true
        val list = p.getString(KEY_PKGS, DEFAULT_PKGS).orEmpty()
        return list.split(',')
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { it == pkg }
    }

    private fun viewId(node: AccessibilityNodeInfo?): String =
        try {
            node?.viewIdResourceName ?: "-"
        } catch (t: Throwable) {
            "?"
        }

    private fun className(node: AccessibilityNodeInfo?): String =
        try {
            node?.className?.toString() ?: "null"
        } catch (t: Throwable) {
            "?"
        }

    private fun typeName(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TEXT_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT_CHANGED"
        else -> type.toString()
    }

    private fun tail(text: String): String {
        val t = text.replace("\n", " ")
        return if (t.length <= 16) t else "…" + t.takeLast(16)
    }

    override fun onInterrupt() {
        addLog("服务被中断")
    }

    override fun onDestroy() {
        polling = false
        handler.removeCallbacksAndMessages(null)
        addLog("服务已停止")
        super.onDestroy()
    }
}
