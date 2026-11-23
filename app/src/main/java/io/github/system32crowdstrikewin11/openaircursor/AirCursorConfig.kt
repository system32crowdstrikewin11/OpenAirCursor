package io.github.system32crowdstrikewin11.openaircursor

import android.content.Context

object AirCursorConfig {
    private const val PREFS = "aircursor_prefs"
    private const val KEY_SENSITIVITY = "sensitivity"
    private const val KEY_SMOOTHNESS = "smoothness"
    private const val KEY_SHOW_PREVIEW = "show_preview"
    private const val KEY_MIRROR_VIEW = "mirror_view"

    // 0.0f..1.0f -> higher sensitivity shrinks the camera active zone (less hand travel needed)
    @Volatile var sensitivity: Float = 0.65f
    // 0.0f..1.0f -> tunes One-Euro filter + display VSync glide
    @Volatile var smoothness: Float = 0.60f
    @Volatile var showPreview: Boolean = true
    @Volatile var mirrorView: Boolean = true

    fun load(context: Context) {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sensitivity = sp.getFloat(KEY_SENSITIVITY, 0.65f).coerceIn(0f, 1f)
        smoothness = sp.getFloat(KEY_SMOOTHNESS, 0.60f).coerceIn(0f, 1f)
        showPreview = sp.getBoolean(KEY_SHOW_PREVIEW, true)
        mirrorView = sp.getBoolean(KEY_MIRROR_VIEW, true)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_SENSITIVITY, sensitivity)
            .putFloat(KEY_SMOOTHNESS, smoothness)
            .putBoolean(KEY_SHOW_PREVIEW, showPreview)
            .putBoolean(KEY_MIRROR_VIEW, mirrorView)
            .apply()
    }

    // Active-zone margin on each side of the camera frame: [0.05 .. 0.34]
    fun activeMargin(): Float = 0.05f + sensitivity * 0.29f
}