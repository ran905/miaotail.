package com.meow.tail

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var suffixEdit: EditText
    private lateinit var triggersEdit: EditText
    private lateinit var pkgsEdit: EditText
    private lateinit var enabledSwitch: Switch
    private lateinit var allAppsCheck: CheckBox
    private lateinit var statusText: TextView
    private lateinit var statusDot: View
    private lateinit var previewText: TextView
    private lateinit var diagText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(TailService.PREFS, MODE_PRIVATE)

        // 首次启动的知情提示：说明它会改写输入文字、不联网、以及安装到他人设备的前提。
        if (!prefs.getBoolean("consent_shown", false)) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.consent_title))
                .setMessage(getString(R.string.consent_message))
                .setPositiveButton(getString(R.string.consent_ok)) { _, _ ->
                    prefs.edit().putBoolean("consent_shown", true).apply()
                }
                .setCancelable(false)
                .show()
        }

        suffixEdit = findViewById(R.id.suffix)
        triggersEdit = findViewById(R.id.triggers)
        pkgsEdit = findViewById(R.id.pkgs)
        enabledSwitch = findViewById(R.id.enabled)
        allAppsCheck = findViewById(R.id.allApps)
        statusText = findViewById(R.id.status)
        statusDot = findViewById(R.id.statusDot)
        previewText = findViewById(R.id.preview)
        diagText = findViewById(R.id.diag)

        suffixEdit.setText(prefs.getString(TailService.KEY_SUFFIX, TailService.DEFAULT_SUFFIX))
        triggersEdit.setText(prefs.getString(TailService.KEY_TRIGGERS, TailService.DEFAULT_TRIGGERS))
        pkgsEdit.setText(prefs.getString(TailService.KEY_PKGS, TailService.DEFAULT_PKGS))
        enabledSwitch.isChecked = prefs.getBoolean(TailService.KEY_ENABLED, true)
        allAppsCheck.isChecked = prefs.getBoolean(TailService.KEY_ALL_APPS, false)

        suffixEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updatePreview()
        })

        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.openSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.copyLog).setOnClickListener { copyLog() }
        findViewById<Button>(R.id.clearLog).setOnClickListener {
            TailService.clearLog()
            renderLog()
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
        }

        updatePreview()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        renderLog()
    }

    private fun save() {
        prefs.edit()
            .putString(TailService.KEY_SUFFIX, suffixEdit.text.toString())
            .putString(TailService.KEY_TRIGGERS, triggersEdit.text.toString())
            .putString(TailService.KEY_PKGS, pkgsEdit.text.toString())
            .putBoolean(TailService.KEY_ENABLED, enabledSwitch.isChecked)
            .putBoolean(TailService.KEY_ALL_APPS, allAppsCheck.isChecked)
            .apply()

        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(suffixEdit.windowToken, 0)

        updateStatus()
        updatePreview()
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus() {
        val enabled = isServiceEnabled()
        statusDot.setBackgroundResource(if (enabled) R.drawable.dot_on else R.drawable.dot_off)
        statusText.text = if (enabled) {
            "已生效。去 QQ / 微信打一句带「。」的话试试。"
        } else {
            "还没开启无障碍服务，点下面的按钮去打开。"
        }
    }

    private fun updatePreview() {
        val suffix = suffixEdit.text.toString().ifEmpty { TailService.DEFAULT_SUFFIX }
        val sample = "今天天气不错${suffix}。"
        val span = SpannableString(sample)
        val start = sample.indexOf(suffix)
        if (start >= 0) {
            val end = start + suffix.length
            span.setSpan(ForegroundColorSpan(getColor(R.color.accent)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            span.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        previewText.text = span
    }

    private fun renderLog() {
        val lines = TailService.snapshotLog()
        diagText.text = if (lines.isEmpty()) {
            "（暂无记录）\n打开无障碍后，去微信里打一句话，再回来看这里。"
        } else {
            lines.joinToString("\n")
        }
    }

    private fun copyLog() {
        val text = TailService.snapshotLog().joinToString("\n")
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("喵尾巴日志", text.ifEmpty { "（暂无记录）" }))
        Toast.makeText(this, "日志已复制，粘贴到聊天里发我", Toast.LENGTH_SHORT).show()
    }

    private fun isServiceEnabled(): Boolean {
        val expected = ComponentName(this, TailService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }
}
