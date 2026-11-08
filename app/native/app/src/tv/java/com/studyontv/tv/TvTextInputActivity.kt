package com.studyontv.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * 电视版填空 / 主观题作答页：网页里的输入框无法用遥控器打字，这里用原生输入框调出系统输入法，
 * 支持遥控器语音键（若电视装有语音识别）。方向键可在输入框和按钮之间移动。
 */
class TvTextInputActivity : Activity() {
    private lateinit var edit: EditText
    private var delivered = false
    private var cancelled = false
    private val requestId get() = intent.getStringExtra("requestId") ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(TvUi.background(this@TvTextInputActivity))
            setPadding((64 * dp).toInt(), (36 * dp).toInt(), (64 * dp).toInt(), (24 * dp).toInt())
        }
        root.addView(TextView(this).apply { text = "填写答案"; textSize = 26f; setTextColor(TvUi.foreground(this@TvTextInputActivity)) })
        val prompt = intent.getStringExtra("prompt").orEmpty()
        if (prompt.isNotBlank()) root.addView(TextView(this).apply {
            text = prompt.take(200); textSize = 16f; setTextColor(TvUi.muted(this@TvTextInputActivity)); maxLines = 3
            setPadding(0, (8 * dp).toInt(), 0, (12 * dp).toInt())
        }, LinearLayout.LayoutParams(-1, -2))
        edit = EditText(this).apply {
            hint = "用遥控器或语音输入，完成后选择“确定”"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            minLines = 2; maxLines = 5; gravity = Gravity.TOP or Gravity.START
            TvUi.field(this)
            setText(savedInstanceState?.getString("draft") ?: intent.getStringExtra("draft").orEmpty())
            setSelection(text.length)
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { finishWith(true); true } else false }
        }
        root.addView(edit, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * dp).toInt() })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun add(label: String, primary: Boolean = false, action: () -> Unit) =
            row.addView(TvUi.button(this, label, primary, action), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = (8 * dp).toInt(); rightMargin = (8 * dp).toInt() })
        add("确定", true) { finishWith(true) }
        if (packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()) add("语音输入") { voice() }
        add("清空") { edit.setText("") }
        add("取消") { finishWith(false) }
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (20 * dp).toInt() })
        setContentView(root)
        edit.requestFocus()
        edit.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // 遥控器的语音键 / 搜索键直接进入语音输入
        if (event.action == KeyEvent.ACTION_DOWN && (event.keyCode == KeyEvent.KEYCODE_SEARCH || event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST)) { voice(); return true }
        return super.dispatchKeyEvent(event)
    }

    private fun voice() {
        try {
            startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN"), 7)
        } catch (_: Exception) { android.widget.Toast.makeText(this, "这台电视没有语音识别", android.widget.Toast.LENGTH_SHORT).show() }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        edit.append(if (edit.text.isEmpty()) spoken else " $spoken")
    }

    private fun finishWith(save: Boolean) {
        if (delivered) return
        delivered = true
        cancelled = !save
        AnswerInput.complete(requestId, if (save) JSONObject().put("text", edit.text.toString()) else null)
        finish()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("draft", edit.text.toString())
    }

    override fun onDestroy() {
        // 遥控器「返回键」退出时不算取消：把已输入的内容带回去，避免辛苦打的字白打（#64）
        if (!delivered && !isChangingConfigurations) {
            val draft = if (::edit.isInitialized) edit.text.toString() else ""
            AnswerInput.complete(requestId, if (draft.isNotBlank()) JSONObject().put("text", draft) else null)
        }
        super.onDestroy()
    }
}
