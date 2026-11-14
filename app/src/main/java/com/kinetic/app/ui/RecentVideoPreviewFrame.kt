package com.kinetic.app.ui

import android.graphics.Bitmap

/**
 * Small decoded-frame cache entry shared by adjacent video segments that use
 * the same source URI.
 */
internal data class RecentVideoPreviewFrame(
    val sourceTime: Float,
    val bitmap: Bitmap
)
