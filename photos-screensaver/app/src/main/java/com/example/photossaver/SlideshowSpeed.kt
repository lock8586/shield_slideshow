package com.example.photossaver

/**
 * How long each photo stays on screen before the next one. Stored in config as the raw
 * second count under "interval_secs"; the menu offers these presets.
 */
enum class SlideshowSpeed(val label: String, val seconds: Int, val desc: String) {
    FAST("Fast", 6, "A new photo every 6 seconds"),
    NORMAL("Normal", 12, "A new photo every 12 seconds"),
    RELAXED("Relaxed", 20, "A new photo every 20 seconds"),
    SLOW("Slow", 40, "A new photo every 40 seconds");

    companion object {
        val DEFAULT = NORMAL

        /** Map a stored second count to its preset; unknown/unset (-1) falls back to default. */
        fun from(seconds: Int): SlideshowSpeed =
            values().firstOrNull { it.seconds == seconds } ?: DEFAULT
    }
}
