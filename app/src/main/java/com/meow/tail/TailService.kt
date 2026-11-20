package com.meow.tail

import android.accessibilityservice.AccessibilityService
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
 * v1.2
 *
 * 相对 v1.1 的变化：
 * 1. 新增「无条件补喵」模式（默认开启）：不依赖句末标点，任何输入都会带上后缀
 * 2. 归位逻辑：停顿 650ms 后，把后缀移到「最后一个标点之前」或「整句末尾」
 *    - 用户继续打字时，后缀会被移到新内容的后面
 *    - 已经带后缀的句子不会重复插入
 * 3. 只在光标位于末尾时动手，避免打乱句中编辑
 * 4. 保留诊断日志
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

        @Synchronized
        fun snapshotLog(): List<String> = logLines.toList()

        @Synchronized
        fun clearLog() {
            logLines.clear()
        }

        @Synchronized
        fun addLog(line: String) {
            val stamp = timeFormat.format(Date())
            logLines.addFirst("$stamp  $line")
            while (logLines.size > MAX_LOG) {
                logLines.removeLast()
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val lastText = HashMap<String, String>()
    private var lastChangeAt = 0L
    private var lastSelfWriteAt = 0L
    private var polling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                if (isIdle()) checkFocused("轮询")
            } catch (t: Throwable) {
                addLog("轮询异常 ${t.javaClass.simpleName}")
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val settleRunnable = Runnable {
        try {
            if (isIdle()) checkFocused("归位")
            else scheduleSettle()
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
        addLog("事件 ${typeName(type)} $pkg")

        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            val node = event.source ?: focusedNode() ?: return
            handle(node, pkg, "事件", immediate = true)
        }
        scheduleSettle()
    }

    private fun checkFocused(source: String) {
        if (System.currentTimeMillis() - lastSelfWriteAt < SELF_WRITE_WINDOW_MS) return
        val node = focusedNode() ?: return
        val pkg = node.packageName?.toString() ?: return
        if (!isAllowed(pkg)) return
        handle(node, pkg, source, immediate = false)
    }

    private fun focusedNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }

    private fun handle(node: AccessibilityNodeInfo, pkg: String, source: String, immediate: Boolean) {
        if (node.isPassword) return

        val suffix = prefs().getString(KEY_SUFFIX, DEFAULT_SUFFIX).orEmpty()
        val triggers = prefs().getString(KEY_TRIGGERS, DEFAULT_TRIGGERS).orEmpty()
        if (suffix.isEmpty() || triggers.isEmpty()) return

        val text = node.text?.toString() ?: return
        if (text.isEmpty()) return

        val previous = lastText[pkg]
        lastText[pkg] = text
        if (previous == text) return

        // 用户正在句中编辑时不动，避免打乱光标
        val selectionEnd = node.textSelectionEnd
        if (selectionEnd >= 0 && selectionEnd != text.length) return

        val always = prefs().getBoolean(KEY_ALWAYS, true)
        // 立即路径只处理「标点刚敲下」，其余交给停顿后的归位
        if (immediate && triggers.indexOf(text.last()) < 0) return

        val newText = normalize(text, suffix, triggers, always) ?: return
        if (previous == newText) return

        if (writeText(node, text, newText)) {
            lastText[pkg] = newText
            addLog("补字成功 [$source] $pkg «${tail(newText)}»")
        } else {
            addLog("写入失败 [$source] $pkg")
        }
    }

    /**
     * 把文本整理成「内容 + 后缀 + 末尾标点」。
     * 返回 null 表示不需要改动。
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

        // 去掉已经存在的后缀（最后一次出现），无论它被挤到哪
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
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText,
            )
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false

        val selectionEnd = node.textSelectionEnd
        if (selectionEnd >= 0) {
            val target = selectionEnd + (newText.length - oldText.length)
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, target)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, target)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        }
        return true
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
