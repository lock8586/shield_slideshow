package com.example.photossaver

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class SetupActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN

        // Allow configuring the NAS URL via ADB:
        // adb shell am start -n com.example.photossaver/.SetupActivity --es nas_url "http://192.168.1.43/photos/"
        intent.getStringExtra("nas_url")?.takeIf { it.isNotEmpty() }?.let { url ->
            getSharedPreferences("config", Context.MODE_PRIVATE).edit().putString("nas_url", url).apply()
        }
        intent.getStringExtra("theme")?.takeIf { it.isNotEmpty() }?.let { key ->
            getSharedPreferences("config", Context.MODE_PRIVATE).edit().putString("theme", key).apply()
        }

        // Headless self-test for ADB:
        // adb shell am start -n com.example.photossaver/.SetupActivity --ez selftest true ; adb logcat -s PSSELFTEST
        if (intent.getBooleanExtra("selftest", false)) {
            val url = getSharedPreferences("config", Context.MODE_PRIVATE).getString("nas_url", "") ?: ""
            executor.execute {
                android.util.Log.i("PSSELFTEST", "fetching from: $url")
                val ok = PhotoFetcher(this).testFetch(url, count = 5)
                android.util.Log.i("PSSELFTEST", "RESULT: downloaded $ok files")
            }
        }

        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val existing = prefs.getString("nas_url", "") ?: ""
        if (existing.isNotEmpty()) showReady(existing) else showSetup()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    private fun root(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(100, 80, 100, 80)
            setBackgroundColor(Color.parseColor("#111111"))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111111"))
            addView(layout)
        })
        return layout
    }

    private fun showSetup() {
        val layout = root()
        layout.addView(TextView(this).apply {
            text = "Welps Picture Slideshow"
            textSize = 30f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        })
        layout.addView(TextView(this).apply {
            text = "Enter the NAS photo URL (the folder that serves manifest.txt).\n\nExample:  http://192.168.1.43/photos/"
            textSize = 18f
            setTextColor(Color.parseColor("#AAAAAA"))
            setPadding(0, 0, 0, 32)
        })

        val urlField = EditText(this).apply {
            hint = "http://192.168.1.43/photos/"
            textSize = 18f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.DKGRAY)
            setBackgroundColor(Color.parseColor("#2a2a2a"))
            setPadding(20, 16, 20, 16)
        }
        layout.addView(urlField)
        layout.addView(View(this).apply { minimumHeight = 24 })

        val status = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER_HORIZONTAL
        }

        layout.addView(Button(this).apply {
            text = "Test & Save"
            textSize = 18f
            setOnClickListener {
                val url = urlField.text.toString().trim()
                if (url.isEmpty()) return@setOnClickListener
                status.text = "Testing…"
                if (status.parent == null) layout.addView(status)
                executor.execute {
                    val ok = PhotoFetcher(this@SetupActivity).testFetch(url, count = 5)
                    handler.post {
                        if (ok > 0) {
                            getSharedPreferences("config", Context.MODE_PRIVATE)
                                .edit().putString("nas_url", url).apply()
                            showReady(url, photoCount = ok)
                        } else {
                            status.text = "Couldn't load photos — check the URL and that manifest.txt is reachable."
                        }
                    }
                }
            }
        })
        layout.addView(status)
    }

    private val accent = Color.parseColor("#4CAF50")

    private fun showReady(url: String, photoCount: Int = -1) {
        val layout = root()

        // ── Header ──────────────────────────────────────────────────────────────
        layout.addView(TextView(this).apply {
            text = "Welps Picture Slideshow"
            textSize = 32f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 10)
        })
        layout.addView(TextView(this).apply {
            text = "●  Ready"
            textSize = 17f
            setTextColor(accent)
            setPadding(0, 0, 0, 6)
        })
        layout.addView(TextView(this).apply {
            text = "Streaming from  $url\nTurn on at: Settings → Device Preferences → Screen Saver → Welps Picture Slideshow"
            textSize = 14f
            setTextColor(Color.parseColor("#7A7A7A"))
            setPadding(0, 0, 0, 36)
            setLineSpacing(8f, 1f)
        })

        // ── Slideshow style ─────────────────────────────────────────────────────
        layout.addView(TextView(this).apply {
            text = "SLIDESHOW STYLE"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#888888"))
            letterSpacing = 0.12f
            setPadding(0, 0, 0, 18)
        })
        val current = Theme.from(
            getSharedPreferences("config", Context.MODE_PRIVATE).getString("theme", null)
        )
        var focusTarget: View? = null
        for (t in Theme.values()) {
            val selected = t == current
            val card = themeCard(t, selected) {
                getSharedPreferences("config", Context.MODE_PRIVATE)
                    .edit().putString("theme", t.key).apply()
                showReady(url, photoCount)
            }
            if (selected) focusTarget = card
            layout.addView(card)
        }

        // ── Footer ──────────────────────────────────────────────────────────────
        layout.addView(View(this).apply { minimumHeight = 28 })
        layout.addView(pillButton("Change NAS URL", filled = false) {
            getSharedPreferences("config", Context.MODE_PRIVATE).edit().remove("nas_url").apply()
            showSetup()
        })

        // Keep the remote focused on the selected style after a re-render.
        focusTarget?.let { layout.post { it.requestFocus() } }
    }

    private fun descFor(t: Theme): String = when (t) {
        Theme.THIS_MONTH ->
            "Photos taken in ${SimpleDateFormat("MMMM", Locale.getDefault()).format(Date())} — across all years"
        else -> t.desc
    }

    private fun themeCard(t: Theme, selected: Boolean, onClick: () -> Unit): View {
        val baseStroke = if (selected) accent else Color.parseColor("#2E2E30")
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(40, 30, 40, 30)
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(if (selected) Color.parseColor("#15311C") else Color.parseColor("#1B1B1D"))
                setStroke(if (selected) 4 else 2, baseStroke)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 22 }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has ->
                (v.background as GradientDrawable).setStroke(
                    if (has) 5 else (if (selected) 4 else 2),
                    if (has) Color.WHITE else baseStroke
                )
                v.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f)
                    .setDuration(120).start()
            }
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = if (selected) "●" else "○"
            textSize = 19f
            setTextColor(if (selected) accent else Color.parseColor("#5A5A5A"))
            setPadding(0, 0, 22, 0)
        })
        titleRow.addView(TextView(this).apply {
            text = t.label
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        card.addView(titleRow)
        card.addView(TextView(this).apply {
            text = descFor(t)
            textSize = 14f
            setTextColor(Color.parseColor("#9A9A9A"))
            setPadding(41, 10, 0, 0)
        })
        return card
    }

    private fun pillButton(label: String, filled: Boolean, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            setTextColor(if (filled) Color.BLACK else Color.parseColor("#CCCCCC"))
            stateListAnimator = null
            background = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(if (filled) accent else Color.parseColor("#1B1B1D"))
                setStroke(2, Color.parseColor("#2E2E30"))
            }
            setPadding(40, 20, 40, 20)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { v, has ->
                v.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f)
                    .setDuration(120).start()
                (v.background as GradientDrawable).setStroke(if (has) 4 else 2,
                    if (has) Color.WHITE else Color.parseColor("#2E2E30"))
            }
        }
}
