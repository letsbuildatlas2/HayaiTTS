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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = AtlasTtsDiagnostics.read(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        layout.addView(Button(this).apply {
            text = "Share timing report"
            setOnClickListener {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Atlas TTS timing report")
                    putExtra(Intent.EXTRA_TEXT, content)
                }
                startActivity(Intent.createChooser(share, "Share Atlas diagnostics"))
            }
        })
        layout.addView(TextView(this).apply {
            text = "Atlas speech timings (newest last)\n\n$content"
            textSize = 14f
            setTextIsSelectable(true)
        })
        setContentView(ScrollView(this).apply { addView(layout) })
    }
}
