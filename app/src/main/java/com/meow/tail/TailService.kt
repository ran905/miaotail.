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
 * v1.4
 *
 * 修复 v1.3 及以前的致命逻辑 bug：原先在判断之前就写入 lastText，
 * 导致「事件先到、归位后到」时归位路径认为文本没变化而直接返回，
 * 结果除标点瞬间外永远不会补字。
 *
 * 现在的状态模型：
 * - lastSeen[pkg] 记录「上次看到的文本」，只用于判断用户是否还在输入
 * - 事件路径：文本变化 + 末字符是标点 → 立即补
 * - 归位路径（停顿 650ms）：无条件整理成「内容 + 后缀 + 末尾标点」
 * - 轮询路径：只有连续两次读到同样文本（稳定 400ms）才动手，
 *   避免在拼音组合态里插字
 *
 * 写回失败时回退到「全选 + 剪贴板粘贴」。
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
        addLog("事件 ${typeName(type)} $pkg")

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val node = event.source ?: focusedNode()
            if (node != null) handle(node, pkg, "事件", Mode.EVENT)
        }
        scheduleSettle()
    }

    private fun checkFocused(source: String, mode: Mode) {
        if (System.currentTimeMillis() - lastSelfWriteAt < SELF_WRITE_WINDOW_MS) return
        val node = focusedNode() ?: return
        val pkg = node.packageName?.toString() ?: return
        if (!isAllowed(pkg)) return
        handle(node, pkg, source, mode)
    }

    private fun focusedNode(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

    private fun handle(node: AccessibilityNodeInfo, pkg: String, source: String, mode: Mode) {
        if (node.isPassword) return

        val suffix = prefs().getString(KEY_SUFFIX, DEFAULT_SUFFIX).orEmpty()
        val triggers = prefs().getString(KEY_TRIGGERS, DEFAULT_TRIGGERS).orEmpty()
        if (suffix.isEmpty() || triggers.isEmpty()) return

        val text = node.text?.toString() ?: return
        if (text.isEmpty()) return

        val previous = lastSeen[pkg]
        lastSeen[pkg] = text

        // 事件路径：同一文本重复上报，忽略
        if (mode == Mode.EVENT && previous == text) return

        // 轮询路径：等文本稳定一轮再来，避免打断拼音组合
        if (mode == Mode.POLL && previous != text) return

        // 用户正在句中编辑时不动
        val selectionEnd = node.textSelectionEnd
        if (selectionEnd >= 0 && selectionEnd != text.length) return

        val always = prefs().getBoolean(KEY_ALWAYS, true)
        // 事件路径只在「刚敲下句末标点」时动手，其余交给归位
        if (mode == Mode.EVENT && triggers.indexOf(text.last()) < 0) return

        val newText = normalize(text, suffix, triggers, always) ?: return
        if (newText == text) return

        countAttempt()
        if (writeText(node, text, newText)) {
            lastSeen[pkg] = newText
            countOk()
            addLog("补字成功 [$source] $pkg «${tail(newText)}»")
        } else {
            countFail()
            addLog("写入失败 [$source] $pkg")
        }
    }

    /**
     * 整理成「内容 + 后缀 + 末尾标点」。返回 null 表示不需要改动。
     */
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

    /** 先试 ACTION_SET_TEXT，失败则「全选 + 剪贴板粘贴」。 */
    private fun writeText(node: AccessibilityNodeInfo, oldText: String, newText: String): Boolean {
        lastSelfWriteAt = System.currentTimeMillis()
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

        // 回退：全选 → 粘贴
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
        val selectionEnd = node.textSelectionEnd
        if (selectionEnd < 0) return
        val target = (selectionEnd + (newText.length - oldText.length)).coerceIn(0, newText.length)
        val selection = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, target)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, target)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
    }

    private fun isIdle(): Boolean = System.currentTimeMillis() - lastChangeAt >= IDLE_GATE_MS

    private fun scheduleSettle() {
        handler.removeCallbacks(settleRunnable)
        handler.postDelayed(settleRunnable, SETTLE_DELAY_MS)
    }

    private fun prefs(): SharedPreferences = getSharedPreferences(PREFS, MODE_PRIVATE)

    private fun isAllowed(pkg: String): Boolean {
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
