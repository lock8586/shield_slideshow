package com.example.photossaver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The whole slideshow — image, clock, weather, photo date/location and loading overlay —
 * packaged as a self-contained view. It drives itself off the standard view attach/detach
 * lifecycle, so both [PhotoDreamService] (as the system screensaver) and [PreviewActivity]
 * (launched from the menu) can host it without duplicating any logic.
 */
class SlideshowView(context: Context) : FrameLayout(context) {

    private var executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var imageView: ImageView
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private lateinit var timeText: TextView
    private lateinit var dateText: TextView
    private lateinit var weatherText: TextView
    private lateinit var photoDateText: TextView
    private lateinit var locationText: TextView
    private lateinit var loadingText: TextView
    private lateinit var fetcher: PhotoFetcher
    private var nasBase = ""
    private var theme = Theme.RECENT_MIX
    private var dwellMs = 12_000L     // how long each photo stays up, set from config
    private var started = false
    private val bRecent = mutableListOf<PhotoEntry>() // this/last year
    private val bMid = mutableListOf<PhotoEntry>()    // 1–3 years ago
    private val bOld = mutableListOf<PhotoEntry>()     // older or undated
    private val datePool = mutableListOf<PhotoEntry>() // matches today's date (anniversary themes)
    private val recentlyShown = ArrayDeque<String>()  // avoid near-term repeats
    private var nextUp: PhotoEntry? = null            // pre-picked & prefetched next photo
    private val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())

    init {
        addView(buildLayout())
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }

    private fun start() {
        if (started) return
        started = true
        if (executor.isShutdown) executor = Executors.newSingleThreadExecutor()

        val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
        nasBase = prefs.getString("nas_url", "") ?: ""
        if (nasBase.isEmpty()) { showMessage("Open Welps Picture Slideshow app to configure"); return }
        theme = Theme.from(prefs.getString("theme", null))
        dwellMs = SlideshowSpeed.from(prefs.getInt("interval_secs", -1)).seconds * 1000L

        fetcher = PhotoFetcher(context)
        startClock()
        fetchWeather()
        loadManifest()
    }

    /**
     * Fetches the photo list and, on any failure, retries on a capped backoff instead of
     * getting permanently stuck on an error message. The NAS's photo server is a single
     * unattended Python process with no supervisor (see gen_manifest.py / data-pipeline.md)
     * -- a transient blip is expected to happen occasionally, and previously required
     * manually restarting the screensaver to recover from since start() only ever tried
     * once. Found 2026-08-15: heavy concurrent SSH/curl traffic against the NAS during
     * testing was enough to trigger exactly this.
     */
    private fun loadManifest(attempt: Int = 0) {
        val manifestFile = theme.manifestFile ?: "manifest.txt"
        executor.execute {
            // Pull the photo list (people themes have their own pre-filtered manifest),
            // bucket by age / today's date, and stream on demand.
            val entries = fetcher.fetchManifest(nasBase, manifestFile)
            if (entries.isNotEmpty()) {
                bucketize(entries)
                handler.post { showNext() }
                return@execute
            }
            val retryDelay = minOf(5_000L * (attempt + 1), 60_000L)  // 5s, 10s, 15s... capped at 1 min
            handler.post {
                if (theme.manifestFile != null) {
                    showMessage("No photos yet for “${theme.label}”.\nThe list is still being built on the NAS.")
                } else {
                    showMessage("Can't reach the photo server\nRetrying…")
                }
            }
            handler.postDelayed({ if (started) loadManifest(attempt + 1) }, retryDelay)
        }
    }

    private fun stop() {
        started = false
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        player?.release()
        player = null
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

    private fun pickNext(): PhotoEntry? {
        val pool = poolForPick()
        if (pool.isEmpty()) return null
        repeat(8) {
            val cand = pool.random()
            if (!recentlyShown.contains(cand.path)) return cand
        }
        return pool.random()
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildLayout(): FrameLayout {
        val frame = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

        imageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(imageView)

        // Live Photo video layer, stacked directly above imageView (below the clock/
        // location text and loading overlay added further down). Invisible/transparent
        // at rest; playLivePhoto() cross-fades it in over the still, plays the clip once,
        // then cross-fades back out to reveal the still underneath. See PhotoFetcher's
        // PhotoEntry.videoPath and gen_manifest.py for where the pairing comes from.
        playerView = PlayerView(context).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            alpha = 0f
            visibility = View.INVISIBLE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(playerView)

        // Gradient scrim at bottom
        val scrim = View(context).apply {
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
        val scrimTop = View(context).apply {
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
        val clockBox = LinearLayout(context).apply {
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
        val photoInfoBox = LinearLayout(context).apply {
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
        loadingText = TextView(context).apply {
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
        val loadingOverlay = FrameLayout(context).apply {
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
    ) = TextView(context).apply {
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
        val entry = nextUp ?: pickNext() ?: run { showMessage("No photos found"); return }
        nextUp = pickNext()   // decide the upcoming photo now so we can prefetch it
        recentlyShown.addLast(entry.path)
        while (recentlyShown.size > 60) recentlyShown.removeFirst()

        executor.execute {
            val file = fetcher.getPhoto(nasBase, entry.path)
            val bmp = file?.let { decodeOriented(it) }
            val videoFile = entry.videoPath?.let { fetcher.getVideo(nasBase, it) }
            handler.post {
                if (bmp == null) {
                    // Retry quickly past a failed photo rather than stalling on the dwell timer.
                    handler.postDelayed({ showNext() }, 800L)
                    return@post
                }
                fadeOutLoading()
                if (videoFile != null) {
                    // playLivePhoto schedules the next showNext() itself, once the video has
                    // played through and the crossfade to the still has landed -- the dwell
                    // clock starts from there, not from when playback began.
                    playLivePhoto(videoFile, bmp, file)
                } else {
                    showStill(bmp, file)
                    handler.postDelayed({ showNext() }, dwellMs)
                }
            }
            // Warm the cache for the next photo (and its video, if it has one) so its
            // transition is instant.
            nextUp?.let { n ->
                fetcher.getPhoto(nasBase, n.path)
                n.videoPath?.let { fetcher.getVideo(nasBase, it) }
            }
        }
    }

    private fun showStill(bmp: Bitmap, file: File) {
        imageView.setImageBitmap(bmp)
        imageView.alpha = 0f
        imageView.animate().alpha(1f).setDuration(1500).start()
        showExifLocation(file)
    }

    private fun ensurePlayer(): ExoPlayer {
        var p = player
        if (p == null) {
            p = ExoPlayer.Builder(context).build().apply { volume = 0f }  // muted: screensaver, not a video player
            playerView.player = p
            player = p
        }
        return p
    }

    /**
     * Plays a Live Photo's video component once (muted), crossfading it in over whatever's
     * currently showing -- same as a normal photo arriving. When the clip ends (or errors,
     * or stalls unreasonably long), swaps imageView to the paired still WHILE IT'S STILL
     * HIDDEN beneath the opaque video layer (so the swap itself is invisible), then
     * crossfades the video layer away to reveal it -- landing seamlessly on the still
     * instead of a hard cut. Always ends by scheduling the next showNext(), even on failure.
     */
    private fun playLivePhoto(videoFile: File, stillBmp: Bitmap, stillFile: File) {
        val p = ensurePlayer()
        var settled = false
        lateinit var watchdog: Runnable
        lateinit var listener: Player.Listener

        // Runs exactly once per play (guarded by `settled`), however we got here: the
        // clip finished normally, errored, or the watchdog gave up on it. Always removes
        // this call's own listener first -- p is a long-lived, reused player, so a
        // listener left attached would keep firing (and re-entering settle) for every
        // photo shown after this one.
        fun settle() {
            if (settled) return
            settled = true
            handler.removeCallbacks(watchdog)
            p.removeListener(listener)
            imageView.setImageBitmap(stillBmp)
            imageView.alpha = 1f
            showExifLocation(stillFile)
            if (playerView.alpha > 0f) {
                playerView.animate().alpha(0f).setDuration(600).withEndAction {
                    p.stop()
                    playerView.visibility = View.INVISIBLE
                    handler.postDelayed({ showNext() }, dwellMs)
                }.start()
            } else {
                p.stop()
                playerView.visibility = View.INVISIBLE
                handler.postDelayed({ showNext() }, dwellMs)
            }
        }

        watchdog = Runnable { settle() }
        listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) settle()
            }
            override fun onPlayerError(error: PlaybackException) {
                settle()   // fall back to the still, same as any other failed load
            }
        }
        p.addListener(listener)

        p.setMediaItem(MediaItem.fromUri(Uri.fromFile(videoFile)))
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.prepare()
        p.playWhenReady = true

        playerView.visibility = View.VISIBLE
        playerView.alpha = 0f
        playerView.animate().alpha(1f).setDuration(1500).start()

        // Live Photo clips are only a few seconds -- if something wedges (codec issue,
        // stalled fetch, whatever), don't strand the slideshow waiting for STATE_ENDED
        // that may never come.
        handler.postDelayed(watchdog, 15_000L)
    }

    private fun fadeOutLoading() {
        val overlay = loadingText.parent as? ViewGroup ?: return
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
            val addresses = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
            val place = addresses?.firstOrNull()?.let { addr ->
                // countryName ("France"), not countryCode ("FR"); fall back to the
                // code only when the geocoder gives no name.
                val country = addr.countryName?.takeIf { it.isNotEmpty() } ?: addr.countryCode
                listOfNotNull(addr.locality, addr.adminArea, country).joinToString(", ")
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
            // Full country name ("France"); the ISO code is only a fallback. The
            // Accept-Language header above already asks for it in the device language.
            val country = address.optString("country").takeIf { it.isNotEmpty() }
                ?: address.optString("country_code").uppercase().takeIf { it.isNotEmpty() }
            listOfNotNull(city, state, country).joinToString(", ").takeIf { it.isNotEmpty() }
        } catch (_: Exception) { null }
    }

    private fun showMessage(msg: String) {
        removeAllViews()
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(TextView(context).apply {
                text = msg; textSize = 22f
                setTextColor(Color.parseColor("#888888"))
                gravity = Gravity.CENTER
            })
        })
    }
}
