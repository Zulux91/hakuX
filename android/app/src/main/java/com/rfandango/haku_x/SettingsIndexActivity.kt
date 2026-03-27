package com.rfandango.haku_x

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsIndexActivity : AppCompatActivity() {

  private data class SettingsSection(
    val title: String,
    val subtitle: String,
    val sectionKey: String
  )

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings_index)

    findViewById<View>(R.id.btn_settings_index_back).setOnClickListener { finish() }

    val sections = listOf(
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
        getString(R.string.settings_index_debug),
        getString(R.string.settings_index_debug_desc),
        "debug"
      ),
      SettingsSection(
        getString(R.string.settings_index_eeprom),
        getString(R.string.settings_index_eeprom_desc),
        "eeprom"
      )
    )

    val list = findViewById<LinearLayout>(R.id.settings_index_list)
    for (section in sections) {
      list.addView(createSectionCard(section))
    }
  }

  private fun createSectionCard(section: SettingsSection): View {
    val card = com.google.android.material.card.MaterialCardView(this).apply {
      val lp = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
      ).apply { topMargin = dp(12) }
      layoutParams = lp
      setCardBackgroundColor(resources.getColor(R.color.xemu_surface, theme))
      radius = dp(28).toFloat()
      cardElevation = 0f
      strokeColor = resources.getColor(R.color.xemu_outline_variant, theme)
      strokeWidth = dp(1)
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
    }
    inner.addView(title)

    val subtitle = TextView(this).apply {
      text = section.subtitle
      setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
      setTextColor(resources.getColor(R.color.xemu_text_muted, theme))
      setPadding(0, dp(4), 0, 0)
    }
    inner.addView(subtitle)

    card.addView(inner)

    card.setOnClickListener {
      val intent = Intent(this, SettingsActivity::class.java)
      intent.putExtra("section", section.sectionKey)
      startActivity(intent)
    }

    return card
  }

  private fun dp(value: Int): Int {
    return TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
    ).toInt()
  }
}
