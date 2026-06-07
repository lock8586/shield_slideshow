package com.example.photossaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class PhotoDreamService : DreamService() {

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var imageView: ImageView
    private lateinit var timeText: TextView
    private lateinit var dateText: TextView
    private lateinit var weatherText: TextView
    private lateinit var photoDateText: TextView
    private lateinit var locationText: TextView
    private lateinit var loadingText: TextView
    private lateinit var fetcher: PhotoFetcher
    private var nasBase = ""
    private var theme = Theme.RECENT_MIX
    private val bRecent = mutableListOf<PhotoEntry>() // this/last year
    private val bMid = mutableListOf<PhotoEntry>()    // 1–3 years ago
    private val bOld = mutableListOf<PhotoEntry>()     // older or undated
    private val datePool = mutableListOf<PhotoEntry>() // matches today's date (anniversary themes)
    private val recentlyShown = ArrayDeque<String>()  // avoid near-term repeats
    private var nextUp: String? = null                // pre-picked & prefetched next photo
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFullscreen = true
        isInteractive = false
        setContentView(buildLayout())

        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        nasBase = prefs.getString("nas_url", "") ?: ""
        if (nasBase.isEmpty()) { showMessage("Open Welps Picture Slideshow app to configure"); return }
        theme = Theme.from(prefs.getString("theme", null))

        fetcher = PhotoFetcher(this)
        startClock()
        fetchWeather()

        executor.execute {
            // Pull the full photo list, bucket by age / today's date, and stream on demand.
            val entries = fetcher.fetchManifest(nasBase)
            if (entries.isNotEmpty()) {
                bucketize(entries)
                handler.post { showNext() }
            } else {
                handler.post { showMessage("Can't reach the photo server") }
            }
        }
    }

    private fun bucketize(entries: List<PhotoEntry>) {
        bRecent.clear(); bMid.clear(); bOld.clear(); datePool.clear()
        val cal = Calendar.getInstance()
        val now = cal.get(Calendar.YEAR)
        val todayOrd = (cal.get(Calendar.MONTH) + 1) * 31 + cal.get(Calendar.DAY_OF_MONTH)
        val todayMonth = cal.get(Calendar.MONTH) + 1
        for (e in entries) {
            when {
                e.year == 0 -> bOld.add(e)
                e.year >= now - 1 -> bRecent.add(e)
                e.year >= now - 3 -> bMid.add(e)
                else -> bOld.add(e)
            }
            if (theme.mode == ThemeMode.DATE) {
                val match = if (theme.windowDays <= 0) {
                    e.month == todayMonth
                } else if (e.month in 1..12 && e.day in 1..31) {
                    val diff = Math.abs(e.month * 31 + e.day - todayOrd)
                    Math.min(diff, 372 - diff) <= theme.windowDays
                } else false
                if (match) datePool.add(e)
            }
        }
    }

    private fun poolForPick(): List<PhotoEntry> {
        if (theme.mode == ThemeMode.DATE) {
            // Anniversary themes: today's matches, but never go blank — fall back to recent, then all.
            return datePool.ifEmpty { (bRecent + bMid).ifEmpty { bRecent + bMid + bOld } }
        }
        val weighted = listOf(bRecent to theme.wRecent, bMid to theme.wMid, bOld to theme.wOld)
            .filter { it.first.isNotEmpty() && it.second > 0 }
            .ifEmpty { listOf(bRecent, bMid, bOld).filter { it.isNotEmpty() }.map { it to 1 } }
        if (weighted.isEmpty()) return emptyList()
        var r = (0 until weighted.sumOf { it.second }).random()
        for ((b, w) in weighted) { if (r < w) return b; r -= w }
        return weighted.first().first
    }

    private fun pickNext(): String? {
        val pool = poolForPick()
        if (pool.isEmpty()) return null
        repeat(8) {
            val cand = pool.random()
            if (!recentlyShown.contains(cand.path)) return cand.path
        }
        return pool.random().path
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(): FrameLayout {
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(imageView)

        // Gradient scrim at bottom
        val scrim = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(180, 0, 0, 0), Color.TRANSPARENT)
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 300,
                Gravity.BOTTOM
            )
        }
        frame.addView(scrim)

        // Gradient scrim at top-right for clock
        val scrimTop = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(140, 0, 0, 0), Color.TRANSPARENT)
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, 200,
                Gravity.TOP
            )
        }
        frame.addView(scrimTop)

        // Top-right: time + date
        val clockBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(0, 32, 48, 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            )
        }
        timeText = shadowText(64f, Typeface.BOLD).also { clockBox.addView(it) }
        dateText = shadowText(20f).also { clockBox.addView(it) }
        weatherText = shadowText(20f, color = Color.parseColor("#DDDDDD")).also { clockBox.addView(it) }
        frame.addView(clockBox)

        // Bottom-left: photo date + location
        val photoInfoBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).also { it.setMargins(48, 0, 0, 36) }
        }
        photoDateText = shadowText(16f, color = Color.parseColor("#AAAAAA")).apply {
            gravity = Gravity.START
        }.also { photoInfoBox.addView(it) }
        locationText = shadowText(18f, color = Color.parseColor("#CCCCCC")).apply {
            gravity = Gravity.START
        }.also { photoInfoBox.addView(it) }
        frame.addView(photoInfoBox)

        // Loading screen — photo + text, fades out when first photo is ready
        loadingText = TextView(this).apply {
            text = "Welps Memories are Loading!"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setShadowLayer(6f, 2f, 2f, Color.argb(200, 0, 0, 0))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        val loadingOverlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(ImageView(context).apply {
                setImageResource(R.drawable.loading_photo)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            addView(View(context).apply {
                setBackgroundColor(Color.argb(120, 0, 0, 0))
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            })
            addView(loadingText)
        }
        frame.addView(loadingOverlay)

        return frame
    }

    private fun shadowText(
        size: Float,
        style: Int = Typeface.NORMAL,
        color: Int = Color.WHITE
    ) = TextView(this).apply {
        textSize = size
        setTypeface(typeface, style)
        setTextColor(color)
        setShadowLayer(4f, 2f, 2f, Color.argb(180, 0, 0, 0))
        gravity = Gravity.END
    }

    // ── Clock ─────────────────────────────────────────────────────────────────

    private fun startClock() {
        val tick = object : Runnable {
            override fun run() {
                val now = Date()
                timeText.text = timeFmt.format(now)
                dateText.text = dateFmt.format(now)
                handler.postDelayed(this, 15_000)
            }
        }
        handler.post(tick)
    }

    // ── Weather ───────────────────────────────────────────────────────────────

    private fun fetchWeather() {
        executor.execute {
            try {
                // wttr.in auto-detects location by IP, no API key needed
                val response = URL("https://wttr.in/?format=j1").openStream()
                    .bufferedReader().readText()
                val json = JSONObject(response)
                val current = json.getJSONArray("current_condition").getJSONObject(0)
                val tempF = current.getString("temp_F")
                val desc = current.getJSONArray("weatherDesc").getJSONObject(0).getString("value")
                val area = json.getJSONArray("nearest_area").getJSONObject(0)
                    .getJSONArray("areaName").getJSONObject(0).getString("value")
                handler.post { weatherText.text = "$tempF°F  $desc  ·  $area" }
            } catch (_: Exception) {}
            // Refresh weather every 30 minutes
            handler.postDelayed({ fetchWeather() }, 30 * 60 * 1000L)
        }
    }

    // ── Slideshow ─────────────────────────────────────────────────────────────

    private fun showNext() {
        val relPath = nextUp ?: pickNext() ?: run { showMessage("No photos found"); return }
        nextUp = pickNext()   // decide the upcoming photo now so we can prefetch it
        recentlyShown.addLast(relPath)
        while (recentlyShown.size > 60) recentlyShown.removeFirst()

        executor.execute {
            val file = fetcher.getPhoto(nasBase, relPath)
            val bmp = file?.let { decodeOriented(it) }
            handler.post {
                if (bmp != null) {
                    fadeOutLoading()
                    imageView.setImageBitmap(bmp)
                    imageView.alpha = 0f
                    imageView.animate().alpha(1f).setDuration(1500).start()
                    showExifLocation(file)
                }
                // Move on after 12s on success; retry quickly past a failed/unreachable photo.
                handler.postDelayed({ showNext() }, if (bmp != null) 12_000L else 800L)
            }
            // Warm the cache for the next photo so its transition is instant.
            nextUp?.let { fetcher.getPhoto(nasBase, it) }
        }
    }

    private fun fadeOutLoading() {
        val overlay = loadingText.parent as? android.view.ViewGroup ?: return
        if (overlay.visibility == View.VISIBLE)
            overlay.animate().alpha(0f).setDuration(800).withEndAction {
                overlay.visibility = View.GONE
            }.start()
    }

    // Decode a photo and apply its EXIF orientation so portrait shots aren't shown sideways.
    private fun decodeOriented(file: File): Bitmap? {
        val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return try {
            val orientation = ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val m = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.preScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.preScale(-1f, 1f) }
                else -> return bmp
            }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            rotated
        } catch (_: Exception) {
            bmp
        }
    }

    private val exifDateFmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
    private val photoDateDisplayFmt = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())

    private fun showExifLocation(file: File) {
        executor.execute {
            try {
                val exif = ExifInterface(file.absolutePath)

                val photoDate = (exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME))?.let {
                    try { photoDateDisplayFmt.format(exifDateFmt.parse(it)!!) } catch (_: Exception) { null }
                }

                val latLon = FloatArray(2)
                val place = if (exif.getLatLong(latLon))
                    reverseGeocode(latLon[0].toDouble(), latLon[1].toDouble())
                else null

                handler.post {
                    photoDateText.text = photoDate ?: ""
                    locationText.text = place ?: ""
                }
            } catch (_: Exception) {
                handler.post { photoDateText.text = ""; locationText.text = "" }
            }
        }
    }

    private fun reverseGeocode(lat: Double, lon: Double): String? {
        // Try Android Geocoder first
        try {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(this, Locale.getDefault()).getFromLocation(lat, lon, 1)
            val place = addresses?.firstOrNull()?.let { addr ->
                listOfNotNull(addr.locality, addr.adminArea, addr.countryCode).joinToString(", ")
            }
            if (!place.isNullOrEmpty()) return place
        } catch (_: Exception) {}

        // Fallback: Nominatim open geocoding — works without Google Play Services backend
        return try {
            val conn = URL(
                "https://nominatim.openstreetmap.org/reverse?lat=$lat&lon=$lon&format=json&zoom=10"
            ).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "PhotosScreensaver/1.0")
            conn.setRequestProperty("Accept-Language", Locale.getDefault().language)
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val address = json.optJSONObject("address") ?: return null
            val city = address.optString("city").takeIf { it.isNotEmpty() }
                ?: address.optString("town").takeIf { it.isNotEmpty() }
                ?: address.optString("village").takeIf { it.isNotEmpty() }
            val state = address.optString("state").takeIf { it.isNotEmpty() }
            val country = address.optString("country_code").uppercase().takeIf { it.isNotEmpty() }
            listOfNotNull(city, state, country).joinToString(", ").takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    private fun showMessage(msg: String) {
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(TextView(this@PhotoDreamService).apply {
                text = msg; textSize = 22f
                setTextColor(Color.parseColor("#888888"))
                gravity = Gravity.CENTER
            })
        })
    }
}
