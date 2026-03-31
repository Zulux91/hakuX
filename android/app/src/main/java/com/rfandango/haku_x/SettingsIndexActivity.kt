package com.rfandango.haku_x

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton

class SettingsIndexActivity : AppCompatActivity() {

  companion object {
    const val EXTRA_PER_GAME = "per_game"
    const val EXTRA_GAME_TITLE = "game_title"
    const val EXTRA_GAME_RELATIVE_PATH = "game_relative_path"
  }

  private data class SettingsSection(
    val title: String,
    val subtitle: String,
    val sectionKey: String
  )

  private var isPerGameMode = false
  private var gameTitle: String? = null
  private var gameRelativePath: String? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings_index)

    isPerGameMode = intent.getBooleanExtra(EXTRA_PER_GAME, false)
    gameTitle = intent.getStringExtra(EXTRA_GAME_TITLE)
    gameRelativePath = intent.getStringExtra(EXTRA_GAME_RELATIVE_PATH)

    findViewById<View>(R.id.btn_settings_index_back).setOnClickListener { finish() }

    // Set header title
    val headerTitle = findViewById<TextView>(R.id.settings_index_title)
    if (isPerGameMode && gameTitle != null) {
      headerTitle?.text = gameTitle
    }

    val sections = if (isPerGameMode) {
      listOf(
        SettingsSection(
          getString(R.string.settings_index_graphics),
          getString(R.string.settings_index_graphics_desc),
          "graphics"
        ),
        SettingsSection(
          getString(R.string.settings_index_audio),
          getString(R.string.settings_index_audio_desc),
          "audio"
        ),
        SettingsSection(
          getString(R.string.settings_index_debug),
          getString(R.string.settings_index_debug_desc),
          "debug"
        )
      )
    } else {
      listOf(
        SettingsSection(
          getString(R.string.settings_index_data),
          getString(R.string.settings_index_data_desc),
          "data"
        ),
        SettingsSection(
          getString(R.string.settings_index_graphics),
          getString(R.string.settings_index_graphics_desc),
          "graphics"
        ),
        SettingsSection(
          getString(R.string.settings_index_audio),
          getString(R.string.settings_index_audio_desc),
          "audio"
        ),
        SettingsSection(
          getString(R.string.settings_index_debug),
          getString(R.string.settings_index_debug_desc),
          "debug"
        ),
        SettingsSection(
          getString(R.string.settings_index_eeprom),
          getString(R.string.settings_index_eeprom_desc),
          "eeprom"
        ),
        SettingsSection(
          getString(R.string.settings_index_xbox),
          getString(R.string.settings_index_xbox_desc),
          "xbox"
        )
      )
    }

    val list = findViewById<LinearLayout>(R.id.settings_index_list)
    for (section in sections) {
      val hasOverrides = if (isPerGameMode) sectionHasOverrides(section.sectionKey) else false
      list.addView(createSectionCard(section, hasOverrides))
    }

    // Add "Revert to Global" button in per-game mode
    if (isPerGameMode && gameRelativePath != null) {
      val hasAnyOverrides = PerGameSettingsManager.hasOverrides(this, gameRelativePath!!)
      if (hasAnyOverrides) {
        val revertBtn = MaterialButton(this, null,
          com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
          text = getString(R.string.per_game_settings_revert)
          layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
          ).apply { topMargin = dp(16) }
          setTextColor(Color.parseColor("#F44336"))
          strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336"))
          setOnClickListener {
            PerGameSettingsManager.clearOverrides(this@SettingsIndexActivity, gameRelativePath!!)
            Toast.makeText(this@SettingsIndexActivity,
              R.string.per_game_settings_cleared, Toast.LENGTH_SHORT).show()
            finish()
          }
        }
        list.addView(revertBtn)
      }
    }
  }

  private fun sectionHasOverrides(sectionKey: String): Boolean {
    val path = gameRelativePath ?: return false
    val overrides = PerGameSettingsManager.loadOverrides(this, path)
    val sectionKeys = when (sectionKey) {
      "graphics" -> setOf("gpu_driver", "renderer", "surface_scale", "filtering",
        "aspect_ratio", "fast_fences", "draw_reorder", "draw_merge",
        "async_compile", "frame_skip", "unlock_framerate",
        "submit_frames")
      "audio" -> setOf("use_dsp")
      "debug" -> setOf("fp_safe", "fp_jit", "tier1_threshold", "simple_vblank")
      else -> emptySet()
    }
    return overrides.keys.any { it in sectionKeys }
  }

  private fun createSectionCard(section: SettingsSection, hasOverrides: Boolean): View {
    val overrideColor = 0xFF4CAF50.toInt()

    val card = com.google.android.material.card.MaterialCardView(this).apply {
      val lp = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { topMargin = dp(12) }
      layoutParams = lp
      setCardBackgroundColor(resources.getColor(R.color.xemu_surface, theme))
      radius = dp(28).toFloat()
      cardElevation = 0f
      strokeColor = if (hasOverrides) overrideColor
                    else resources.getColor(R.color.xemu_outline_variant, theme)
      strokeWidth = if (hasOverrides) dp(2) else dp(1)
      isClickable = true
      isFocusable = true

      val ripple = TypedValue()
      context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
      foreground = resources.getDrawable(ripple.resourceId, theme)
    }

    val inner = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(24), dp(20), dp(24), dp(20))
    }

    val title = TextView(this).apply {
      text = section.title
      setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
      if (hasOverrides) setTextColor(overrideColor)
    }
    inner.addView(title)

    val subtitle = TextView(this).apply {
      text = section.subtitle
      setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
      setTextColor(resources.getColor(R.color.xemu_text_muted, theme))
      setPadding(0, dp(4), 0, 0)
    }
    inner.addView(subtitle)

    if (hasOverrides) {
      val badge = TextView(this).apply {
        text = "Has overrides"
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
        setTextColor(overrideColor)
        setPadding(0, dp(4), 0, 0)
      }
      inner.addView(badge)
    }

    card.addView(inner)

    card.setOnClickListener {
      val intent = Intent(this, SettingsActivity::class.java)
      intent.putExtra("section", section.sectionKey)
      if (isPerGameMode) {
        intent.putExtra(EXTRA_PER_GAME, true)
        intent.putExtra(EXTRA_GAME_TITLE, gameTitle)
        intent.putExtra(EXTRA_GAME_RELATIVE_PATH, gameRelativePath)
      }
      startActivity(intent)
    }

    return card
  }

  private var hasNavigatedAway = false

  override fun onPause() {
    super.onPause()
    hasNavigatedAway = true
  }

  override fun onResume() {
    super.onResume()
    // Refresh cards when returning from a settings sub-page
    if (isPerGameMode && hasNavigatedAway) {
      hasNavigatedAway = false
      recreate()
    }
  }

  private fun dp(value: Int): Int {
    return TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
  }
}
