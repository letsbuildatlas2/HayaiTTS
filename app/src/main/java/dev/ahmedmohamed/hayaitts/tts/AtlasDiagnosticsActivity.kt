package dev.ahmedmohamed.hayaitts.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Launcher-accessible diagnostics for phone-only testing; shares text only on user action. */
class AtlasDiagnosticsActivity : Activity() {
    private lateinit var reportView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        layout.addView(Button(this).apply {
            text = "Refresh timing report"
            setOnClickListener { refreshReport() }
        })
        layout.addView(Button(this).apply {
            text = "Share timing report"
            setOnClickListener {
                // Read at tap time; do not share the stale snapshot from activity creation.
                val report = AtlasTtsDiagnostics.read(this@AtlasDiagnosticsActivity)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Atlas TTS timing report")
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                startActivity(Intent.createChooser(share, "Share Atlas diagnostics"))
            }
        })
        reportView = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }
        layout.addView(reportView)
        setContentView(ScrollView(this).apply { addView(layout) })
        refreshReport()
    }

    override fun onResume() {
        super.onResume()
        if (::reportView.isInitialized) refreshReport()
    }

    private fun refreshReport() {
        reportView.text = "Atlas speech timings (newest last)\n\n${AtlasTtsDiagnostics.read(this)}"
    }
}
