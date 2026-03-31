package com.rfandango.haku_x

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class PerGameSettingsActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_GAME_TITLE = "com.rfandango.haku_x.extra.GAME_TITLE"
        const val EXTRA_GAME_RELATIVE_PATH = "com.rfandango.haku_x.extra.GAME_RELATIVE_PATH"
    }

    private val prefs by lazy { getSharedPreferences("x1box_prefs", MODE_PRIVATE) }
    private val overrides = mutableMapOf<String, String?>()
    private lateinit var relativePath: String

    private val overrideColor by lazy { 0xFF4CAF50.toInt() }
    private val mutedColor by lazy { ContextCompat.getColor(this, R.color.xemu_text_muted) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_per_game_settings)

        val gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE)?.trim().orEmpty()
        relativePath = intent.getStringExtra(EXTRA_GAME_RELATIVE_PATH)?.trim().orEmpty()

        if (relativePath.isEmpty()) {
            Toast.makeText(this, R.string.per_game_settings_missing_game, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        findViewById<TextView>(R.id.tv_per_game_settings_game_title).text =
            gameTitle.ifEmpty { relativePath.substringAfterLast('/') }
        findViewById<TextView>(R.id.tv_per_game_settings_game_path).text = relativePath
        findViewById<View>(R.id.btn_per_game_back).setOnClickListener { finish() }

        val saved = PerGameSettingsManager.loadOverrides(this, relativePath)
        overrides.putAll(saved.mapValues { it.value })

        buildSettingsUI()

        findViewById<MaterialButton>(R.id.btn_per_game_settings_clear).setOnClickListener {
            PerGameSettingsManager.clearOverrides(this, relativePath)
            Toast.makeText(this, R.string.per_game_settings_cleared, Toast.LENGTH_SHORT).show()
            finish()
        }
        findViewById<MaterialButton>(R.id.btn_per_game_settings_save).setOnClickListener {
            PerGameSettingsManager.saveOverrides(this, relativePath, overrides)
            Toast.makeText(this, R.string.per_game_settings_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun buildSettingsUI() {
        val container = findViewById<LinearLayout>(R.id.per_game_settings_container)

        GpuDriverHelper.init(this)
        if (GpuDriverHelper.supportsCustomDriverLoading()) {
            val installedName = GpuDriverHelper.getInstalledDriverName()
            val globalDriver = if (installedName != null) "Custom ($installedName)" else "System"
            addPicker(container, "gpu_driver", "GPU Driver",
                arrayOf("Use Global", "System Driver", "Custom Driver"),
                arrayOf(null, "system", "custom"), globalDriver)
        }

        addPicker(container, "renderer", getString(R.string.settings_renderer),
            arrayOf("Use Global", "Vulkan", "OpenGL ES"),
            arrayOf(null, "vulkan", "opengl"),
            globalValue("renderer", "vulkan", mapOf("vulkan" to "Vulkan", "opengl" to "OpenGL ES")))

        addPicker(container, "surface_scale", getString(R.string.settings_resolution_scale),
            arrayOf("Use Global", "1x", "2x", "3x", "4x"),
            arrayOf(null, "1", "2", "3", "4"),
            globalValue("surface_scale", "1", mapOf("1" to "1x", "2" to "2x", "3" to "3x", "4" to "4x")))

        addPicker(container, "filtering", getString(R.string.settings_filtering),
            arrayOf("Use Global", "Linear", "Nearest"),
            arrayOf(null, "linear", "nearest"),
            globalValue("filtering", "linear", mapOf("linear" to "Linear", "nearest" to "Nearest")))

        addPicker(container, "aspect_ratio", getString(R.string.settings_aspect_ratio),
            arrayOf("Use Global", "Native", "4:3", "16:9"),
            arrayOf(null, "native", "4:3", "16:9"),
            globalValue("aspect_ratio", "native", mapOf("native" to "Native", "4:3" to "4:3", "16:9" to "16:9")))

        addPicker(container, "submit_frames", getString(R.string.settings_submit_frames),
            arrayOf("Use Global", "Single", "Double", "Triple"),
            arrayOf(null, "1", "2", "3"),
            globalValue("submit_frames", "2", mapOf("1" to "Single", "2" to "Double", "3" to "Triple")))

        addBoolPicker(container, "frame_skip", getString(R.string.settings_frame_skip), "frame_skip")
        addBoolPicker(container, "unlock_framerate", getString(R.string.settings_unlock_framerate), "unlock_framerate")
        addBoolPicker(container, "fp_safe", getString(R.string.settings_fp_safe), "fp_safe")
        addBoolPicker(container, "fp_jit", getString(R.string.settings_fp_jit), "fp_jit")
        addBoolPicker(container, "fast_fences", getString(R.string.settings_fast_fences), "fast_fences")
        addBoolPicker(container, "draw_reorder", getString(R.string.settings_draw_reorder), "draw_reorder")
        addBoolPicker(container, "draw_merge", getString(R.string.settings_draw_merge), "draw_merge")
        addBoolPicker(container, "async_compile", getString(R.string.settings_async_compile), "async_compile")
    }

    private fun styleOverride(titleView: TextView, button: MaterialButton, isOverridden: Boolean) {
        val color = if (isOverridden) overrideColor else mutedColor
        val typeface = if (isOverridden) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        titleView.setTextColor(color)
        titleView.typeface = typeface
        button.setTextColor(color)
        button.strokeColor = android.content.res.ColorStateList.valueOf(color)
    }

    private fun addPicker(
        container: LinearLayout,
        key: String,
        label: String,
        displayLabels: Array<String>,
        values: Array<String?>,
        globalDisplay: String,
    ) {
        // Title — matches activity_settings.xml: TitleSmall, marginTop=20dp
        val titleView = TextView(this).apply {
            text = label
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        }
        container.addView(titleView)

        // Button — matches: materialButtonOutlinedStyle, match_parent, marginTop=8dp
        val button = MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setTextColor(mutedColor)
        }

        fun updateDisplay() {
            val current = overrides[key]
            val isOverridden = current != null
            val idx = values.indexOf(current)
            button.text = if (isOverridden && idx >= 0) displayLabels[idx] else "Use Global"
            styleOverride(titleView, button, isOverridden)
        }
        updateDisplay()

        button.setOnClickListener {
            val sel = values.indexOf(overrides[key]).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle(label)
                .setSingleChoiceItems(displayLabels, sel) { dialog, which ->
                    overrides[key] = values[which]
                    updateDisplay()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        container.addView(button)

        // Description — matches: BodySmall, xemu_text_muted, marginStart=4dp, marginTop=2dp
        container.addView(TextView(this).apply {
            text = "Global: $globalDisplay"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(mutedColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4)
                topMargin = dp(2)
            }
        })
    }

    private fun addBoolPicker(
        container: LinearLayout,
        key: String,
        label: String,
        prefsKey: String,
    ) {
        val globalVal = prefs.getBoolean(prefsKey, false)
        val displayLabels = arrayOf("Use Global", "Enabled", "Disabled")
        val values = arrayOf<String?>(null, "true", "false")

        val titleView = TextView(this).apply {
            text = label
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20) }
        }
        container.addView(titleView)

        val button = MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setTextColor(mutedColor)
        }

        fun updateDisplay() {
            val current = overrides[key]
            val isOverridden = current != null
            button.text = when (current) {
                "true" -> "Enabled"
                "false" -> "Disabled"
                else -> "Use Global"
            }
            styleOverride(titleView, button, isOverridden)
        }
        updateDisplay()

        button.setOnClickListener {
            val sel = values.indexOf(overrides[key]).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle(label)
                .setSingleChoiceItems(displayLabels, sel) { dialog, which ->
                    overrides[key] = values[which]
                    updateDisplay()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        container.addView(button)

        container.addView(TextView(this).apply {
            text = "Global: ${if (globalVal) "Enabled" else "Disabled"}"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(mutedColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4)
                topMargin = dp(2)
            }
        })
    }

    private fun globalValue(key: String, default: String, labelMap: Map<String, String>): String {
        val value = when (key) {
            "surface_scale", "submit_frames" -> prefs.getInt(key, default.toInt()).toString()
            else -> prefs.getString(key, default) ?: default
        }
        return labelMap[value] ?: value
    }
}
