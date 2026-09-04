package com.jklee.poptube

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 앱 안 진단 화면.
 *
 * 이 프로젝트는 실기기 로그를 한 번도 못 봐서 "증상 보고 → 추측 수정 → 빌드 4분 → 재실패" 를
 * 여섯 번 반복했다(HANDOFF §7.1). USB 없이 사용자가 스크린샷 한 장 또는 공유 한 번으로
 * 상황 전체를 넘길 수 있게 하는 것이 이 화면의 존재 이유다.
 *
 * 레이아웃은 XML 없이 코드로 만든다 — 리소스가 늘면 프록시 우회 업로더(tools/push.ps1)로
 * 올려야 할 파일만 많아진다.
 */
class DiagnosticActivity : AppCompatActivity() {

    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        body = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(PAD, PAD, PAD, PAD)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(PAD, 0, PAD, PAD)
            addView(button("복사") { copyToClipboard() }, equalWidth())
            addView(button("공유") { share() }, equalWidth())
            addView(button("새로고침") { render() }, equalWidth())
            addView(button("지우기") { clearAll() }, equalWidth())
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(
                TextView(this@DiagnosticActivity).apply {
                    text = getString(R.string.diagnostic_title)
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setPadding(PAD, PAD, PAD, 0)
                },
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            )
            addView(
                ScrollView(this@DiagnosticActivity).apply { addView(body) },
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            )
            addView(buttons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        setContentView(root)
        render()
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            textSize = 12f
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun equalWidth() =
        LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { gravity = Gravity.CENTER }

    private fun render() {
        body.text = report()
    }

    /** 화면에 그대로 보이고, 복사·공유에도 같은 내용이 나간다. */
    private fun report(): String = buildString {
        append(DiagnosticLog.snapshot(this@DiagnosticActivity))
        DiagnosticLog.lastCrash(this@DiagnosticActivity)?.let {
            append("\n\n━━ 마지막 크래시 ━━\n").append(it)
        }
        append("\n\n━━ 로그 ━━\n")
        val lines = DiagnosticLog.dump()
        if (lines.isEmpty()) append("(비어 있음)") else lines.forEach { append(it).append('\n') }
    }

    private fun copyToClipboard() {
        val clip = getSystemService(ClipboardManager::class.java)
        clip?.setPrimaryClip(ClipData.newPlainText("PopTube 진단", report()))
        Toast.makeText(this, R.string.diagnostic_copied, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "PopTube 진단")
            putExtra(Intent.EXTRA_TEXT, report())
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.diagnostic_share))) }
    }

    private fun clearAll() {
        DiagnosticLog.clear()
        DiagnosticLog.clearCrash(this)
        render()
    }

    companion object {
        private const val PAD = 24

        fun open(context: Context) {
            runCatching { context.startActivity(Intent(context, DiagnosticActivity::class.java)) }
        }
    }
}
