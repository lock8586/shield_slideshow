package com.example.photossaver

import android.service.dreams.DreamService

/**
 * The system screensaver. All the actual slideshow work lives in [SlideshowView], which is
 * also reused by [PreviewActivity] so the menu can launch the same experience on demand.
 */
class PhotoDreamService : DreamService() {

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isFullscreen = true
        isInteractive = false
        setContentView(SlideshowView(this))
    }
}
