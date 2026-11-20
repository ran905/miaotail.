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
 * v1.1
 *
 * 相比 v1.0 的改动：
 * 1. 双通道检测：无障碍事件 + 400ms 轮询焦点输入框，谁先看到谁处理
 *    （微信/QQ 等 App 不一定广播文本变化事件，轮询是兜底）
 * 2. 触发点改为「最后一个句末标点」，并且判断该标点前后是否已经带后缀，
 *    避免「你好喵。今天」这种已经补过的句子被重复插入
 * 3. 内置诊断日志：最近 40 条事件/决策，可在 App 里查看并复制
 * 4. 自己写回后 400ms 内不再处理，避免自激
 */
class TailService : AccessibilityService() {

    companion object {
        const val PREFS = "miao_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_SUFFIX = "suffix"
        const val KEY_TRIGGERS = "triggers"
        const val KEY_ALL_APPS = "all_apps"
        const val KEY_PKGS = "pkg_list"

        const val DEFAULT_SUFFIX = "喵"
        const val DEFAULT_TRIGGERS = "。！？!?～"
        const val DEFAULT_PKGS = "com.tencent.mm,com.tencent.mobileqq,com.tencent.tim"

        private const val MAX_LOG = 40
        private const val POLL_INTERVAL_MS = 400L
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
    private var lastSelfWriteAt = 0L
    private var polling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                checkFocused("轮询")
            } catch (t: Throwable) {
                addLog("轮询异常 ${t.javaClass.simpleName}")
            }
            handler.postDelayed(this, POLL_INTERVAL_MS)
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

        addLog("事件 ${typeName(type)} $pkg")
        val node = event.source
        if (node != null && type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            handleNode(node, pkg, "事件")
        } else {
            checkFocused("事件")
        }
    }

    private fun checkFocused(source: String) {
        if (System.currentTimeMillis() - lastSelfWriteAt < SELF_WRITE_WINDOW_MS) return
        val root = rootInActiveWindow ?: return
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val pkg = node.packageName?.toString() ?: return
        if (!isAllowed(pkg)) return
        handleNode(node, pkg, source)
    }

    private fun handleNode(node: AccessibilityNodeInfo, pkg: String, source: String) {
        if (node.isPassword) return

        val suffix = prefs().getString(KEY_SUFFIX, DEFAULT_SUFFIX).orEmpty()
        val triggers = prefs().getString(KEY_TRIGGERS, DEFAULT_TRIGGERS).orEmpty()
        if (suffix.isEmpty() || triggers.isEmpty()) return

        val text = node.text?.toString() ?: return
        if (text.isEmpty()) return

        val previous = lastText[pkg]
        lastText[pkg] = text
        if (previous == text) return

        // 只看最后一个句末标点
        var index = -1
        for (i in text.length - 1 downTo 0) {
            if (triggers.indexOf(text[i]) >= 0) {
                index = i
                break
            }
        }
        if (index < 0) return

        // 已经补过：标点前面是后缀，或者标点后面紧跟后缀
        val alreadyDone = (index > 0 && text.substring(0, index).endsWith(suffix)) ||
            (index + 1 < text.length && text.startsWith(suffix, index + 1))
        if (alreadyDone) return

        val head = text.substring(0, index)
        if (head.isBlank()) return

        val insertAt = index + 1
        val newText = text.substring(0, insertAt) + suffix + text.substring(insertAt)
        val selectionStart = node.textSelectionStart
        val selectionEnd = node.textSelectionEnd

        lastSelfWriteAt = System.currentTimeMillis()
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText,
            )
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) {
            addLog("写入失败 [$source] $pkg")
            return
        }

        if (selectionStart >= 0 && selectionEnd >= 0) {
            val start = if (selectionStart >= insertAt) selectionStart + suffix.length else selectionStart
            val end = if (selectionEnd >= insertAt) selectionEnd + suffix.length else selectionEnd
            val selection = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selection)
        }

        lastText[pkg] = newText
        addLog("补字成功 [$source] $pkg «${tail(text)}»")
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
