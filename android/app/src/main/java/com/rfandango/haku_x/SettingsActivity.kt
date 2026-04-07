package com.rfandango.haku_x

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SettingsActivity : AppCompatActivity() {
  private val prefs by lazy { getSharedPreferences("x1box_prefs", MODE_PRIVATE) }

  // Per-game mode state
  private var isPerGameMode = false
  private var gameRelativePath: String? = null
  private val gameOverrides = mutableMapOf<String, String?>()
  private val overrideColor = 0xFF4CAF50.toInt()
  private val mutedColor: Int get() = resources.getColor(R.color.xemu_text_muted, theme)

  /** Read bool, per-game override first */
  private fun pgBool(key: String, def: Boolean): Boolean {
    if (isPerGameMode) gameOverrides[key]?.let { return it == "true" }
    return prefs.getBoolean(key, def)
  }
  /** Read int, per-game override first */
  private fun pgInt(key: String, def: Int): Int {
    if (isPerGameMode) gameOverrides[key]?.let { return it.toIntOrNull() ?: def }
    return prefs.getInt(key, def)
  }
  /** Read string, per-game override first */
  private fun pgStr(key: String, def: String): String {
    if (isPerGameMode) gameOverrides[key]?.let { return it }
    return prefs.getString(key, def) ?: def
  }
  // Global defaults for boolean settings (must match what native code uses)
  private val boolDefaults = mapOf(
    "fp_safe" to true, "fp_jit" to true, "unlock_framerate" to true,
    "fast_fences" to false, "skip_occlusion_queries" to false,
    "draw_reorder" to false, "draw_merge" to false,
    "async_compile" to false, "frame_skip" to false,
    "use_dsp" to false,
    "skip_boot_anim" to true,
    "texture_dump_enabled" to false,
    "texture_replace_enabled" to false
  )
  private val intDefaults = mapOf(
    "surface_scale" to 1, "submit_frames" to 3, "tier1_threshold" to 64,
    "texture_cache_size" to 0
  )
  private val strDefaults = mapOf(
    "renderer" to "vulkan", "filtering" to "nearest", "aspect_ratio" to "auto"
  )

  /** Write setting. In per-game mode, stores override only if different from global. */
  private fun pgPutBool(key: String, v: Boolean) {
    if (isPerGameMode) {
      val globalVal = prefs.getBoolean(key, boolDefaults[key] ?: false)
      if (v == globalVal) gameOverrides.remove(key)
      else gameOverrides[key] = v.toString()
    } else {
      prefs.edit().putBoolean(key, v).apply()
    }
  }
  private fun pgPutInt(key: String, v: Int) {
    if (isPerGameMode) {
      val globalVal = prefs.getInt(key, intDefaults[key] ?: 0)
      if (v == globalVal) gameOverrides.remove(key)
      else gameOverrides[key] = v.toString()
    } else {
      prefs.edit().putInt(key, v).apply()
    }
  }
  private fun pgPutStr(key: String, v: String) {
    if (isPerGameMode) {
      val globalVal = prefs.getString(key, strDefaults[key] ?: "") ?: ""
      if (v == globalVal) gameOverrides.remove(key)
      else gameOverrides[key] = v
    } else {
      prefs.edit().putString(key, v).apply()
    }
  }

  /** Apply or remove highlight on a view based on per-game override state. */
  private fun updateHighlight(key: String, vararg views: View) {
    if (!isPerGameMode) return
    val isOverridden = gameOverrides.containsKey(key)
    val dp = resources.displayMetrics.density
    for (v in views) {
      when (v) {
        is com.google.android.material.materialswitch.MaterialSwitch -> {
          v.setTextColor(if (isOverridden) overrideColor else mutedColor)
          v.setTypeface(null, if (isOverridden) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        is MaterialButton -> {
          v.setTextColor(if (isOverridden) overrideColor else mutedColor)
          v.strokeColor = android.content.res.ColorStateList.valueOf(
            if (isOverridden) overrideColor else mutedColor)
          v.strokeWidth = if (isOverridden) (2 * dp).toInt() else (1 * dp).toInt()
        }
      }
      // For buttons inside group wrappers, highlight the title label
      // (first plain TextView in the group). Skip for switches — their
      // text IS the label and is already colored above.
      if (v is MaterialButton) {
        val parent = v.parent as? android.view.ViewGroup ?: continue
        for (j in 0 until parent.childCount) {
          val child = parent.getChildAt(j)
          if (child is TextView &&
              child !is android.widget.Button &&
              child !is com.google.android.material.materialswitch.MaterialSwitch) {
            child.setTextColor(if (isOverridden) overrideColor else mutedColor)
            child.setTypeface(null, if (isOverridden) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            break
          }
        }
      }
    }
  }

  /** Convenience alias for initial setup */
  private fun highlightIfOverridden(key: String, vararg views: View) = updateHighlight(key, *views)

  /** Generic setup for a per-game-overridable boolean switch. */
  private fun setupSwitch(switchId: Int, key: String, default: Boolean,
                          nativeCallback: ((Boolean) -> Unit)? = null) {
    val sw = findViewById<MaterialSwitch>(switchId)
    sw.isChecked = pgBool(key, default)
    sw.setOnCheckedChangeListener { _, checked ->
      pgPutBool(key, checked)
      if (nativeCallback != null && !isPerGameMode) {
        try { nativeCallback(checked) } catch (_: Throwable) {}
      }
      updateHighlight(key, sw)
    }
    highlightIfOverridden(key, sw)
  }

  /** Generic setup for a per-game-overridable int picker button. */
  private fun setupIntPicker(buttonId: Int, key: String, default: Int, titleRes: Int,
                             labels: Array<String>, values: IntArray,
                             nativeCallback: ((Int) -> Unit)? = null) {
    val btn = findViewById<MaterialButton>(buttonId)
    val idx = values.indexOf(pgInt(key, default)).coerceAtLeast(0)
    btn.text = labels[idx]
    highlightIfOverridden(key, btn)
    btn.setOnClickListener {
      val sel = values.indexOf(pgInt(key, default)).coerceAtLeast(0)
      MaterialAlertDialogBuilder(this)
        .setTitle(titleRes)
        .setSingleChoiceItems(labels, sel) { dialog, which ->
          pgPutInt(key, values[which])
          if (nativeCallback != null && !isPerGameMode) {
            try { nativeCallback(values[which]) } catch (_: Throwable) {}
          }
          btn.text = labels[which]
          updateHighlight(key, btn)
          dialog.dismiss()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    }
  }

  /** Generic setup for a per-game-overridable string picker button. */
  private fun setupStringPicker(buttonId: Int, key: String, default: String, titleRes: Int,
                                labels: Array<String>, values: Array<String>,
                                postAction: (() -> Unit)? = null) {
    val btn = findViewById<MaterialButton>(buttonId)
    val idx = values.indexOf(pgStr(key, default)).coerceAtLeast(0)
    btn.text = labels[idx]
    highlightIfOverridden(key, btn)
    btn.setOnClickListener {
      val sel = values.indexOf(pgStr(key, default)).coerceAtLeast(0)
      MaterialAlertDialogBuilder(this)
        .setTitle(titleRes)
        .setSingleChoiceItems(labels, sel) { dialog, which ->
          pgPutStr(key, values[which])
          btn.text = labels[which]
          updateHighlight(key, btn)
          dialog.dismiss()
          postAction?.invoke()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
    }
  }

  private data class EepromLanguageOption(val value: XboxEepromEditor.Language, val labelRes: Int)
  private data class EepromVideoOption(val value: XboxEepromEditor.VideoStandard, val labelRes: Int)
  private data class EepromAspectRatioOption(val value: XboxEepromEditor.AspectRatio, val labelRes: Int)
  private data class EepromRefreshRateOption(val value: XboxEepromEditor.RefreshRate, val labelRes: Int)

  private data class DashboardImportPlan(
    val hddFile: File,
    val workingDir: File,
    val sourceDir: File,
    val backupDir: File,
    val summary: String,
    val bootNote: String?,
    val bootAliasCreated: Boolean,
    val retailBootReady: Boolean,
  )

  private data class DashboardBootPreparation(
    val note: String?,
    val aliasCreated: Boolean,
    val retailBootReady: Boolean,
  )

  private data class InsigniaStatusSnapshot(
    val hasLocalHdd: Boolean,
    val hasEeprom: Boolean,
    val dashboardStatus: XboxInsigniaHelper.DashboardStatus?,
    val dashboardError: String?,
    val setupAssistantName: String?,
    val setupAssistantReady: Boolean,
  )

  private val eepromLanguageOptions = listOf(
    EepromLanguageOption(XboxEepromEditor.Language.ENGLISH, R.string.settings_eeprom_language_english),
    EepromLanguageOption(XboxEepromEditor.Language.JAPANESE, R.string.settings_eeprom_language_japanese),
    EepromLanguageOption(XboxEepromEditor.Language.GERMAN, R.string.settings_eeprom_language_german),
    EepromLanguageOption(XboxEepromEditor.Language.FRENCH, R.string.settings_eeprom_language_french),
    EepromLanguageOption(XboxEepromEditor.Language.SPANISH, R.string.settings_eeprom_language_spanish),
    EepromLanguageOption(XboxEepromEditor.Language.ITALIAN, R.string.settings_eeprom_language_italian),
    EepromLanguageOption(XboxEepromEditor.Language.KOREAN, R.string.settings_eeprom_language_korean),
    EepromLanguageOption(XboxEepromEditor.Language.CHINESE, R.string.settings_eeprom_language_chinese),
    EepromLanguageOption(XboxEepromEditor.Language.PORTUGUESE, R.string.settings_eeprom_language_portuguese),
  )
  private val eepromVideoOptions = listOf(
    EepromVideoOption(XboxEepromEditor.VideoStandard.NTSC_M, R.string.settings_eeprom_video_standard_ntsc_m),
    EepromVideoOption(XboxEepromEditor.VideoStandard.NTSC_J, R.string.settings_eeprom_video_standard_ntsc_j),
    EepromVideoOption(XboxEepromEditor.VideoStandard.PAL_I, R.string.settings_eeprom_video_standard_pal_i),
    EepromVideoOption(XboxEepromEditor.VideoStandard.PAL_M, R.string.settings_eeprom_video_standard_pal_m),
  )
  private val eepromAspectRatioOptions = listOf(
    EepromAspectRatioOption(XboxEepromEditor.AspectRatio.NORMAL, R.string.settings_eeprom_aspect_ratio_normal),
    EepromAspectRatioOption(XboxEepromEditor.AspectRatio.WIDESCREEN, R.string.settings_eeprom_aspect_ratio_widescreen),
    EepromAspectRatioOption(XboxEepromEditor.AspectRatio.LETTERBOX, R.string.settings_eeprom_aspect_ratio_letterbox),
  )
  private val eepromRefreshRateOptions = listOf(
    EepromRefreshRateOption(XboxEepromEditor.RefreshRate.DEFAULT, R.string.settings_eeprom_refresh_rate_default),
    EepromRefreshRateOption(XboxEepromEditor.RefreshRate.HZ_60, R.string.settings_eeprom_refresh_rate_60),
    EepromRefreshRateOption(XboxEepromEditor.RefreshRate.HZ_50, R.string.settings_eeprom_refresh_rate_50),
  )

  private lateinit var tvEepromStatus: TextView
  private lateinit var inputEepromLanguage: TextInputLayout
  private lateinit var inputEepromVideoStandard: TextInputLayout
  private lateinit var inputEepromAspectRatio: TextInputLayout
  private lateinit var inputEepromRefreshRate: TextInputLayout
  private lateinit var dropdownEepromLanguage: AutoCompleteTextView
  private lateinit var dropdownEepromVideoStandard: AutoCompleteTextView
  private lateinit var dropdownEepromAspectRatio: AutoCompleteTextView
  private lateinit var dropdownEepromRefreshRate: AutoCompleteTextView
  private lateinit var switchEeprom480p: MaterialSwitch
  private lateinit var switchEeprom720p: MaterialSwitch
  private lateinit var switchEeprom1080i: MaterialSwitch

  private var selectedEepromLanguage = XboxEepromEditor.Language.ENGLISH
  private var selectedEepromVideoStandard = XboxEepromEditor.VideoStandard.NTSC_M
  private var selectedEepromAspectRatio = XboxEepromEditor.AspectRatio.NORMAL
  private var selectedEepromRefreshRate = XboxEepromEditor.RefreshRate.DEFAULT
  private var eepromEditable = false
  private var eepromMissing = false
  private var eepromError = false

  private var isInitializingHdd = false
  private var isImportingDashboard = false
  private var isPreparingInsignia = false

  private lateinit var tvInsigniaStatus: TextView
  private lateinit var tvHddToolsStatus: TextView
  private lateinit var switchNetworkEnable: MaterialSwitch
  private lateinit var btnInsigniaGuide: MaterialButton
  private lateinit var btnInsigniaSignUp: MaterialButton
  private lateinit var btnPrepareInsignia: MaterialButton
  private lateinit var btnRegisterInsignia: MaterialButton
  private lateinit var btnDashboardImportZip: MaterialButton
  private lateinit var btnDashboardImportFolder: MaterialButton
  private lateinit var btnInitializeRetailHdd: MaterialButton

  private lateinit var driverStatusText: TextView
  private lateinit var gpuNotSupportedText: TextView
  private lateinit var btnInstallDriver: MaterialButton
  private lateinit var btnSelectDriver: MaterialButton
  private lateinit var btnResetDriver: MaterialButton
  private lateinit var switchShowFps: MaterialSwitch

  private lateinit var mcpxPathText: TextView
  private lateinit var flashPathText: TextView
  private lateinit var hddPathText: TextView
  private lateinit var gamesFolderPathText: TextView

  private val romExts = setOf("bin", "rom", "img")
  private val hddExts = setOf("qcow2", "img")

  private val pickDriverZip =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        installDriverFromUri(uri)
      }
    }

  private val pickMcpx =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        copySystemFile(uri, "mcpx.bin", "mcpxPath", "mcpxUri") { updateMcpxPath() }
      }
    }

  private val pickFlash =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        copySystemFile(uri, "flash.bin", "flashPath", "flashUri") { updateFlashPath() }
      }
    }

  private val pickHdd =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) {
        copySystemFile(uri, "hdd.img", "hddPath", "hddUri") { updateHddPath() }
      }
    }

  private val exportHdd =
    registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
      if (uri != null) {
        exportHddToUri(uri)
      }
    }

  private val pickLogDir =
    registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) dumpLogsToDir(uri)
    }

  private val pickGamesFolder =
    registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) {
        try {
          contentResolver.takePersistableUriPermission(uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {}
        prefs.edit().putString("gamesFolderUri", uri.toString()).apply()
        updateGamesFolderPath()
      }
    }

  private val pickTextureDumpFolder =
    registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) {
        try {
          contentResolver.takePersistableUriPermission(uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {}
        prefs.edit().putString("textureDumpFolderUri", uri.toString()).apply()
        updateTextureDumpPath()
      }
    }

  private val pickTextureReplaceFolder =
    registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
      if (uri != null) {
        try {
          contentResolver.takePersistableUriPermission(uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: SecurityException) {}
        prefs.edit().putString("textureReplaceFolderUri", uri.toString()).apply()
        updateTextureReplacePath()
      }
    }

  private val pickDashboardZip =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
      uri ?: return@registerForActivityResult
      persistUriPermission(uri)
      if (!isZipSelection(uri)) {
        Toast.makeText(this, R.string.settings_dashboard_import_pick_zip_error, Toast.LENGTH_LONG).show()
        return@registerForActivityResult
      }
      prepareDashboardImportFromZip(uri)
    }

  private val pickDashboardFolder =
    registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
      uri ?: return@registerForActivityResult
      persistUriPermission(uri)
      prepareDashboardImportFromFolder(uri)
    }

  private val pickInsigniaSetupAssistant =
    registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
      uri ?: return@registerForActivityResult
      persistUriPermission(uri)

      val name = getFileName(uri)
        ?: uri.lastPathSegment
        ?: getString(R.string.settings_insignia_setup_source_unknown)
      prefs.edit()
        .putString(PREF_INSIGNIA_SETUP_URI, uri.toString())
        .putString(PREF_INSIGNIA_SETUP_NAME, name)
        .apply()

      refreshInsigniaStatus()
      launchInsigniaSetupAssistant(uri)
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings)

    // Per-game mode init
    isPerGameMode = intent.getBooleanExtra(SettingsIndexActivity.EXTRA_PER_GAME, false)
    gameRelativePath = intent.getStringExtra(SettingsIndexActivity.EXTRA_GAME_RELATIVE_PATH)
    if (isPerGameMode && gameRelativePath != null) {
      val saved = PerGameSettingsManager.loadOverrides(this, gameRelativePath!!)
      gameOverrides.putAll(saved.mapValues { it.value })
      val title = intent.getStringExtra(SettingsIndexActivity.EXTRA_GAME_TITLE)
        ?: gameRelativePath!!.substringAfterLast('/')
      findViewById<android.widget.TextView>(R.id.settings_header_title)?.text = title
    }

    driverStatusText = findViewById(R.id.settings_gpu_driver_status)
    gpuNotSupportedText = findViewById(R.id.settings_gpu_not_supported)
    btnInstallDriver = findViewById(R.id.btn_install_driver)
    btnSelectDriver = findViewById(R.id.btn_select_driver)
    btnResetDriver = findViewById(R.id.btn_reset_driver)
    switchShowFps = findViewById(R.id.switch_show_fps)

    tvEepromStatus = findViewById(R.id.tv_eeprom_status)
    inputEepromLanguage = findViewById(R.id.input_eeprom_language)
    inputEepromVideoStandard = findViewById(R.id.input_eeprom_video_standard)
    inputEepromAspectRatio = findViewById(R.id.input_eeprom_aspect_ratio)
    inputEepromRefreshRate = findViewById(R.id.input_eeprom_refresh_rate)
    dropdownEepromLanguage = findViewById(R.id.dropdown_eeprom_language)
    dropdownEepromVideoStandard = findViewById(R.id.dropdown_eeprom_video_standard)
    dropdownEepromAspectRatio = findViewById(R.id.dropdown_eeprom_aspect_ratio)
    dropdownEepromRefreshRate = findViewById(R.id.dropdown_eeprom_refresh_rate)
    switchEeprom480p = findViewById(R.id.switch_eeprom_480p)
    switchEeprom720p = findViewById(R.id.switch_eeprom_720p)
    switchEeprom1080i = findViewById(R.id.switch_eeprom_1080i)

    mcpxPathText = findViewById(R.id.settings_mcpx_path)
    flashPathText = findViewById(R.id.settings_flash_path)
    hddPathText = findViewById(R.id.settings_hdd_path)
    gamesFolderPathText = findViewById(R.id.settings_games_folder_path)
    updateMcpxPath()
    updateFlashPath()
    updateHddPath()
    updateGamesFolderPath()
    findViewById<MaterialButton>(R.id.btn_pick_mcpx).setOnClickListener {
      pickMcpx.launch(arrayOf("application/octet-stream"))
    }
    findViewById<MaterialButton>(R.id.btn_pick_flash).setOnClickListener {
      pickFlash.launch(arrayOf("application/octet-stream"))
    }
    findViewById<MaterialButton>(R.id.btn_pick_hdd).setOnClickListener {
      pickHdd.launch(arrayOf("application/x-qcow2", "application/octet-stream"))
    }
    findViewById<MaterialButton>(R.id.btn_pick_games_folder).setOnClickListener {
      val currentUri = prefs.getString("gamesFolderUri", null)?.let(Uri::parse)
      pickGamesFolder.launch(currentUri)
    }

    findViewById<View>(R.id.btn_settings_back).setOnClickListener { finish() }

    GpuDriverHelper.init(this)

    val supportsCustom = GpuDriverHelper.supportsCustomDriverLoading()

    if (!supportsCustom) {
      gpuNotSupportedText.visibility = View.VISIBLE
      btnInstallDriver.isEnabled = false
      btnSelectDriver.isEnabled = false
      btnResetDriver.isEnabled = false
    }

    btnInstallDriver.setOnClickListener {
      pickDriverZip.launch(arrayOf("application/zip", "application/octet-stream"))
    }

    btnSelectDriver.setOnClickListener {
      showDriverSelectionDialog()
    }

    btnResetDriver.setOnClickListener {
      confirmResetDriver()
    }

    switchShowFps.isChecked = prefs.getBoolean("show_fps", true)
    switchShowFps.setOnCheckedChangeListener { _, checked ->
      prefs.edit().putBoolean("show_fps", checked).apply()
    }

    setupSwitch(R.id.switch_skip_boot_anim, "skip_boot_anim", true)
    setupSwitch(R.id.switch_unlock_framerate, "unlock_framerate", true)
    setupSwitch(R.id.switch_fp_safe, "fp_safe", true) { nativeSetFpSafe(it) }
    setupSwitch(R.id.switch_fp_jit, "fp_jit", true) { nativeSetFpJit(it) }
    setupSwitch(R.id.switch_fast_fences, "fast_fences", false) { nativeSetFastFences(it) }
    setupSwitch(R.id.switch_skip_occlusion, "skip_occlusion_queries", false)
    setupSwitch(R.id.switch_draw_reorder, "draw_reorder", false) { nativeSetDrawReorder(it) }
    setupSwitch(R.id.switch_draw_merge, "draw_merge", false) { nativeSetDrawMerge(it) }
    setupSwitch(R.id.switch_async_compile, "async_compile", false) { nativeSetAsyncCompile(it) }
    setupSwitch(R.id.switch_frame_skip, "frame_skip", false) { nativeSetFrameSkip(it) }

    setupIntPicker(R.id.btn_texture_cache, "texture_cache_size", 0,
      R.string.settings_texture_cache, arrayOf("Auto (0)", "512", "1024", "2048"), intArrayOf(0, 512, 1024, 2048))
    setupIntPicker(R.id.btn_submit_frames, "submit_frames", 3,
      R.string.settings_submit_frames, arrayOf("Single (1)", "Double (2)", "Triple (3)"), intArrayOf(1, 2, 3)) { nativeSetSubmitFrames(it) }
    setupIntPicker(R.id.btn_tier1_threshold, "tier1_threshold", 64,
      R.string.settings_tier1_threshold, arrayOf("Disabled", "Aggressive (16)", "Early (32)", "Default (64)", "Conservative (128)", "Lazy (256)"), intArrayOf(0, 16, 32, 64, 128, 256)) { nativeSetTier1Threshold(it) }

    val switchValidation = findViewById<MaterialSwitch>(R.id.switch_validation_layers)
    switchValidation.isChecked = prefs.getBoolean("validation_layers", false)
    switchValidation.setOnCheckedChangeListener { _, checked ->
      prefs.edit().putBoolean("validation_layers", checked).apply()
    }

    val switchDebugTools = findViewById<MaterialSwitch>(R.id.switch_debug_tools)
    switchDebugTools.isChecked = prefs.getBoolean("debug_tools", false)
    switchDebugTools.setOnCheckedChangeListener { _, checked ->
      prefs.edit().putBoolean("debug_tools", checked).apply()
    }

    // Texture Dump
    val switchTextureDump = findViewById<MaterialSwitch>(R.id.switch_texture_dump)
    val btnTextureDumpFolder = findViewById<MaterialButton>(R.id.btn_texture_dump_folder)
    val txtTextureDumpPath = findViewById<TextView>(R.id.txt_texture_dump_path)

    switchTextureDump.isChecked = pgBool("texture_dump_enabled", false)
    btnTextureDumpFolder.isEnabled = switchTextureDump.isChecked && !isPerGameMode
    updateTextureDumpPath()

    switchTextureDump.setOnCheckedChangeListener { _, checked ->
      pgPutBool("texture_dump_enabled", checked)
      btnTextureDumpFolder.isEnabled = checked && !isPerGameMode
      if (!isPerGameMode) {
        try { nativeSetTextureDumpEnabled(checked) } catch (_: Throwable) {}
        if (checked) applyTextureDumpPath()
      }
      updateHighlight("texture_dump_enabled", switchTextureDump)
    }
    highlightIfOverridden("texture_dump_enabled", switchTextureDump)

    // Hide folder picker in per-game mode (path is global)
    if (isPerGameMode) {
      btnTextureDumpFolder.visibility = android.view.View.GONE
      txtTextureDumpPath.visibility = android.view.View.GONE
    }

    btnTextureDumpFolder.setOnClickListener {
      val currentUri = prefs.getString("textureDumpFolderUri", null)
      val launchUri = if (currentUri != null) android.net.Uri.parse(currentUri) else null
      pickTextureDumpFolder.launch(launchUri)
    }

    // Custom Textures (Replacement)
    val switchTextureReplace = findViewById<MaterialSwitch>(R.id.switch_texture_replace)
    val btnTextureReplaceFolder = findViewById<MaterialButton>(R.id.btn_texture_replace_folder)
    val txtTextureReplacePath = findViewById<TextView>(R.id.txt_texture_replace_path)

    switchTextureReplace.isChecked = pgBool("texture_replace_enabled", false)
    btnTextureReplaceFolder.isEnabled = switchTextureReplace.isChecked && !isPerGameMode
    updateTextureReplacePath()

    switchTextureReplace.setOnCheckedChangeListener { _, checked ->
      pgPutBool("texture_replace_enabled", checked)
      btnTextureReplaceFolder.isEnabled = checked && !isPerGameMode
      if (!isPerGameMode) {
        try { nativeSetTextureReplaceEnabled(checked) } catch (_: Throwable) {}
        if (checked) applyTextureReplacePath()
      }
      updateHighlight("texture_replace_enabled", switchTextureReplace)
    }
    highlightIfOverridden("texture_replace_enabled", switchTextureReplace)

    // Hide folder picker in per-game mode (path is global)
    if (isPerGameMode) {
      btnTextureReplaceFolder.visibility = android.view.View.GONE
      txtTextureReplacePath.visibility = android.view.View.GONE
    }

    btnTextureReplaceFolder.setOnClickListener {
      val currentUri = prefs.getString("textureReplaceFolderUri", null)
      val launchUri = if (currentUri != null) android.net.Uri.parse(currentUri) else null
      pickTextureReplaceFolder.launch(launchUri)
    }

    findViewById<MaterialButton>(R.id.btn_clear_cache).setOnClickListener {
      confirmClearCache()
    }

    findViewById<MaterialButton>(R.id.btn_clear_shader_cache).setOnClickListener {
      confirmClearShaderCache()
    }

    findViewById<MaterialButton>(R.id.btn_clear_code_cache).setOnClickListener {
      clearCodeCache()
    }

    findViewById<MaterialButton>(R.id.btn_dump_logs).setOnClickListener {
      pickLogDir.launch(null)
    }

    findViewById<MaterialButton>(R.id.btn_recreate_hdd).setOnClickListener {
      confirmRecreateHdd()
    }

    findViewById<MaterialButton>(R.id.btn_export_hdd).setOnClickListener {
      val hddPath = resolveHddPath()
      if (hddPath == null) {
        Toast.makeText(this, getString(R.string.settings_export_hdd_no_file), Toast.LENGTH_LONG).show()
      } else {
        exportHdd.launch("hdd.img")
      }
    }

    // Audio - DSP
    setupSwitch(R.id.switch_use_dsp, "use_dsp", false)

    setupStringPicker(R.id.btn_renderer, "renderer", "vulkan",
      R.string.settings_renderer, arrayOf("Vulkan", "OpenGL ES"), arrayOf("vulkan", "opengl")) {
      if (!isPerGameMode) clearAllShaderCaches()
    }
    setupIntPicker(R.id.btn_resolution_scale, "surface_scale", 1,
      R.string.settings_resolution_scale, arrayOf("1x (Native)", "2x", "3x", "4x"), intArrayOf(1, 2, 3, 4))
    setupStringPicker(R.id.btn_filtering, "filtering", "nearest",
      R.string.settings_filtering, arrayOf("Nearest", "Linear"), arrayOf("nearest", "linear"))
    setupStringPicker(R.id.btn_aspect_ratio, "aspect_ratio", "auto",
      R.string.settings_aspect_ratio, arrayOf("Auto", "Native (4:3)", "16:9", "Fit"), arrayOf("auto", "native", "16:9", "fit"))
    setupEnvVars()
    setupEepromEditor()

    findViewById<MaterialButton>(R.id.btn_save_eeprom).setOnClickListener {
      applyEepromEdits()
    }

    // Xbox dashboard management sections
    tvInsigniaStatus = findViewById(R.id.tv_insignia_status)
    tvHddToolsStatus = findViewById(R.id.tv_hdd_tools_status)
    switchNetworkEnable = findViewById(R.id.switch_online_enable)
    btnInsigniaGuide = findViewById(R.id.btn_insignia_guide)
    btnInsigniaSignUp = findViewById(R.id.btn_insignia_sign_up)
    btnPrepareInsignia = findViewById(R.id.btn_insignia_prepare)
    btnRegisterInsignia = findViewById(R.id.btn_insignia_register)
    btnDashboardImportZip = findViewById(R.id.btn_dashboard_import_zip)
    btnDashboardImportFolder = findViewById(R.id.btn_dashboard_import_folder)
    btnInitializeRetailHdd = findViewById(R.id.btn_hdd_init)

    setupOnlineSection()
    setupHddToolsSection()
    setupDashboardImportSection()
    refreshInsigniaStatus()
    refreshHddToolsState()

    refreshDriverStatus()

    // Show only the relevant sections based on the index selection
    val section = intent.getStringExtra("section")
    if (section != null) {
      applySectionFilter(section)
    }
  }

  override fun onPause() {
    super.onPause()
    if (isPerGameMode && gameRelativePath != null) {
      PerGameSettingsManager.saveOverrides(this, gameRelativePath!!, gameOverrides)
    }
  }

  private fun applySectionFilter(section: String) {
    val allSections = listOf(
      R.id.section_games_folder,
      R.id.section_system_files,
      R.id.section_hard_disk,
      R.id.section_display,
      R.id.card_gpu_driver,
      R.id.section_env_vars,
      R.id.section_audio,
      R.id.section_debug,
      R.id.section_eeprom,
      R.id.section_online,
      R.id.section_hdd_tools,
      R.id.section_dashboard_import
    )

    val (title, visibleSections) = when (section) {
      "data" -> getString(R.string.settings_index_data) to
        setOf(R.id.section_games_folder, R.id.section_system_files, R.id.section_hard_disk)
      "graphics" -> getString(R.string.settings_index_graphics) to
        setOf(R.id.section_display, R.id.card_gpu_driver, R.id.section_env_vars)
      "audio" -> getString(R.string.settings_index_audio) to
        setOf(R.id.section_audio)
      "debug" -> getString(R.string.settings_index_debug) to
        setOf(R.id.section_debug)
      "eeprom" -> getString(R.string.settings_index_eeprom) to
        setOf(R.id.section_eeprom)
      "xbox" -> getString(R.string.settings_index_xbox) to
        setOf(R.id.section_online, R.id.section_hdd_tools, R.id.section_dashboard_import)
      else -> return
    }

    if (!isPerGameMode) {
      findViewById<android.widget.TextView>(R.id.settings_header_title)?.text = title
    }

    for (id in allSections) {
      findViewById<View>(id)?.visibility =
        if (id in visibleSections) View.VISIBLE else View.GONE
    }

    // In per-game mode, hide non-overridable widgets within visible sections
    if (isPerGameMode) {
      val hideInPerGame = listOf(
        R.id.group_show_fps,
        R.id.group_validation_layers,
        R.id.group_debug_tools,
        R.id.group_clear_shader_cache,
        R.id.group_clear_code_cache,
        R.id.group_export_logs,
        R.id.section_env_vars
      )
      for (id in hideInPerGame) {
        findViewById<View>(id)?.visibility = View.GONE
      }
      // Hide entire GPU driver section
      findViewById<View>(R.id.card_gpu_driver)?.visibility = View.GONE
    }
  }

  private fun refreshDriverStatus() {
    val name = GpuDriverHelper.getInstalledDriverName()
    if (name != null) {
      driverStatusText.text = getString(R.string.settings_gpu_driver_active, name)
    } else {
      driverStatusText.text = getString(R.string.settings_gpu_driver_system)
    }
  }

  private fun clearAllShaderCaches() {
    val tag = "ShaderCache"
    try {
      val baseDir = filesDir
      // VK caches
      val spvDir = File(baseDir, "spv_cache")
      if (spvDir.isDirectory) {
        spvDir.listFiles()?.forEach { it.delete() }
        spvDir.delete()
      }
      File(baseDir, "vk_pipeline_cache.bin").delete()
      File(baseDir, "shader_module_keys.bin").delete()
      // GL shader binary cache (stored per-shader in gl_shader_cache/)
      val glDir = File(baseDir, "gl_shader_cache")
      if (glDir.isDirectory) {
        glDir.listFiles()?.forEach { it.delete() }
        glDir.delete()
      }
      Log.i(tag, "All shader caches cleared")
    } catch (e: Exception) {
      Log.e(tag, "Failed to clear shader caches", e)
    }
  }

  private fun setupEnvVars() {
    val editText = findViewById<android.widget.EditText>(R.id.edit_env_vars)
    val btnSave = findViewById<MaterialButton>(R.id.btn_save_env_vars)
    editText.setText(prefs.getString("env_vars", "") ?: "")
    btnSave.setOnClickListener {
      prefs.edit().putString("env_vars", editText.text.toString()).apply()
      Toast.makeText(this, "Applied on next game launch", Toast.LENGTH_SHORT).show()
    }
  }

  private fun installDriverFromUri(uri: Uri) {
    Thread {
      val success = GpuDriverHelper.installDriverFromUri(this, uri)
      runOnUiThread {
        if (success) {
          Toast.makeText(this, getString(R.string.settings_gpu_driver_installed), Toast.LENGTH_SHORT).show()
          refreshDriverStatus()
        } else {
          Toast.makeText(this, getString(R.string.settings_gpu_driver_install_failed), Toast.LENGTH_SHORT).show()
        }
      }
    }.start()
  }

  private fun showDriverSelectionDialog() {
    val drivers = GpuDriverHelper.getAvailableDrivers()
    if (drivers.isEmpty()) {
      Toast.makeText(this, getString(R.string.settings_gpu_driver_none_available), Toast.LENGTH_SHORT).show()
      return
    }

    val labels = drivers.map { driver ->
      buildString {
        append(driver.name ?: "Unknown")
        if (!driver.description.isNullOrBlank()) {
          append("\n")
          append(driver.description)
        }
        if (!driver.author.isNullOrBlank()) {
          append("\nby ")
          append(driver.author)
        }
      }
    }.toTypedArray()

    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.settings_gpu_driver_select_title)
      .setItems(labels) { _, which ->
        val selected = drivers[which]
        if (selected.path != null) {
          val zipFile = java.io.File(selected.path)
          val success = GpuDriverHelper.installDriver(zipFile)
          if (success) {
            Toast.makeText(this, getString(R.string.settings_gpu_driver_installed), Toast.LENGTH_SHORT).show()
            refreshDriverStatus()
          } else {
            Toast.makeText(this, getString(R.string.settings_gpu_driver_install_failed), Toast.LENGTH_SHORT).show()
          }
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun confirmResetDriver() {
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.settings_gpu_driver_reset_title)
      .setMessage(R.string.settings_gpu_driver_reset_message)
      .setPositiveButton(R.string.settings_gpu_driver_reset) { _, _ ->
        GpuDriverHelper.installDefaultDriver()
        Toast.makeText(this, getString(R.string.settings_gpu_driver_reset_done), Toast.LENGTH_SHORT).show()
        refreshDriverStatus()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun resolveHddPath(): String? {
    val tag = "XemuHddCache"
    val direct = prefs.getString("hddPath", null)
    Log.i(tag, "resolveHddPath: pref hddPath='${direct ?: "(null)"}'")
    if (!direct.isNullOrEmpty()) {
      val f = File(direct)
      Log.i(tag, "  direct exists=${f.exists()} length=${if (f.exists()) f.length() else -1}")
      if (f.exists()) return direct
    }
    val extDir = getExternalFilesDir(null)
    Log.i(tag, "  extDir=${extDir?.absolutePath ?: "(null)"}")
    if (extDir != null) {
      val extPath = extDir.absolutePath + "/x1box/hdd.img"
      val ef = File(extPath)
      Log.i(tag, "  extPath=$extPath exists=${ef.exists()} length=${if (ef.exists()) ef.length() else -1}")
      if (ef.exists()) return extPath
    }
    val internalPath = filesDir.absolutePath + "/x1box/hdd.img"
    val inf = File(internalPath)
    Log.i(tag, "  internalPath=$internalPath exists=${inf.exists()} length=${if (inf.exists()) inf.length() else -1}")
    if (inf.exists()) return internalPath
    Log.w(tag, "  no HDD image found at any candidate path")
    return null
  }

  private fun dumpLogsToDir(treeUri: android.net.Uri) {
    Toast.makeText(this, getString(R.string.settings_dump_logs_saving), Toast.LENGTH_SHORT).show()
    Thread {
      try {
        val pid = android.os.Process.myPid()
        val timestamp = java.text.SimpleDateFormat(
          "yyyy-MM-dd_HHmmss", java.util.Locale.US
        ).format(java.util.Date())
        val fileName = "hakux_log_${timestamp}.txt"

        val dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, treeUri)
        val file = dir?.createFile("text/plain", fileName)
        if (file == null) {
          runOnUiThread { Toast.makeText(this, getString(R.string.settings_dump_logs_failed), Toast.LENGTH_LONG).show() }
          return@Thread
        }

        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "--pid=$pid"))
        val output = contentResolver.openOutputStream(file.uri)
        if (output == null) {
          runOnUiThread { Toast.makeText(this, getString(R.string.settings_dump_logs_failed), Toast.LENGTH_LONG).show() }
          return@Thread
        }

        process.inputStream.use { input -> output.use { out -> input.copyTo(out) } }
        process.waitFor()

        runOnUiThread {
          Toast.makeText(this, getString(R.string.settings_dump_logs_success, fileName), Toast.LENGTH_LONG).show()
        }
      } catch (e: Exception) {
        runOnUiThread {
          Toast.makeText(this, getString(R.string.settings_dump_logs_failed), Toast.LENGTH_LONG).show()
        }
      }
    }.start()
  }

  private fun clearCodeCache() {
    val paths = listOfNotNull(
      filesDir.absolutePath + "/x1box/tb_cache.bin",
      getExternalFilesDir(null)?.let { it.absolutePath + "/x1box/tb_cache.bin" }
    )
    var deleted = false
    for (path in paths) {
      val f = File(path)
      if (f.exists() && f.delete()) {
        deleted = true
        Log.i("SettingsActivity", "Deleted code cache: $path")
      }
    }
    Toast.makeText(this, getString(R.string.settings_clear_code_cache_done), Toast.LENGTH_SHORT).show()
  }

  private fun confirmClearShaderCache() {
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.settings_clear_shader_cache_confirm_title)
      .setMessage(R.string.settings_clear_shader_cache_confirm_message)
      .setPositiveButton(R.string.settings_clear_shader_cache) { _, _ ->
        try {
          val tag = "XemuShaderCache"
          val baseDir = filesDir
          Log.i(tag, "clearShaderCache: baseDir=${baseDir.absolutePath}")

          val spvDir = File(baseDir, "spv_cache")
          if (spvDir.isDirectory) {
            val files = spvDir.listFiles()
            Log.i(tag, "  spv_cache: ${files?.size ?: 0} files")
            files?.forEach { it.delete() }
            val rmDir = spvDir.delete()
            Log.i(tag, "  spv_cache dir removed=$rmDir")
          } else {
            Log.i(tag, "  spv_cache: not found")
          }

          val plc = File(baseDir, "vk_pipeline_cache.bin")
          Log.i(tag, "  vk_pipeline_cache.bin exists=${plc.exists()} deleted=${plc.delete()}")
          val smk = File(baseDir, "shader_module_keys.bin")
          Log.i(tag, "  shader_module_keys.bin exists=${smk.exists()} deleted=${smk.delete()}")

          Toast.makeText(this, getString(R.string.settings_clear_shader_cache_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
          Log.e("XemuShaderCache", "clearShaderCache failed", e)
          Toast.makeText(this, getString(R.string.settings_clear_shader_cache_failed, e.message), Toast.LENGTH_LONG).show()
        }
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun confirmClearCache() {
    val tag = "XemuHddCache"
    val hddPath = resolveHddPath()
    if (hddPath == null) {
      Log.e(tag, "confirmClearCache: no HDD path resolved")
      Toast.makeText(this, getString(R.string.settings_clear_cache_no_hdd), Toast.LENGTH_LONG).show()
      return
    }
    Log.i(tag, "confirmClearCache: resolved path=$hddPath")
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.settings_clear_cache_confirm_title)
      .setMessage(R.string.settings_clear_cache_confirm_message)
      .setPositiveButton(R.string.settings_clear_cache) { _, _ ->
        Toast.makeText(this, getString(R.string.settings_clear_cache_working), Toast.LENGTH_SHORT).show()
        Thread {
          try {
            clearHddCachePartitions(hddPath)
            runOnUiThread {
              Toast.makeText(this, getString(R.string.settings_clear_cache_success), Toast.LENGTH_SHORT).show()
            }
          } catch (e: Exception) {
            Log.e(tag, "clearHddCachePartitions failed", e)
            runOnUiThread {
              Toast.makeText(this, getString(R.string.settings_clear_cache_failed, e.message), Toast.LENGTH_LONG).show()
            }
          }
        }.start()
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun confirmRecreateHdd() {
    val tag = "XemuHddRecreate"
    val sourceUriStr = prefs.getString("hddUri", null)
    if (sourceUriStr.isNullOrEmpty()) {
      Log.e(tag, "confirmRecreateHdd: no source URI in prefs")
      Toast.makeText(this, getString(R.string.settings_recreate_hdd_no_source), Toast.LENGTH_LONG).show()
      return
    }

    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.settings_recreate_hdd_confirm_title)
      .setMessage(R.string.settings_recreate_hdd_confirm_message)
      .setPositiveButton(R.string.settings_recreate_hdd) { _, _ ->
        recreateHddFromSource(sourceUriStr, tag)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun recreateHddFromSource(sourceUriStr: String, tag: String) {
    Toast.makeText(this, getString(R.string.settings_recreate_hdd_copying), Toast.LENGTH_SHORT).show()

    Thread {
      try {
        val sourceUri = Uri.parse(sourceUriStr)
        val base = getExternalFilesDir(null) ?: filesDir
        val dir = File(base, "x1box")
        if (!dir.exists() && !dir.mkdirs()) {
          throw IOException("Failed to create directory ${dir.absolutePath}")
        }
        val target = File(dir, "hdd.img")

        Log.i(tag, "recreateHdd: source=$sourceUriStr target=${target.absolutePath}")

        contentResolver.openInputStream(sourceUri)?.use { input ->
          FileOutputStream(target).use { output ->
            input.copyTo(output)
          }
        } ?: throw IOException("Unable to open source HDD image")

        val newPath = target.absolutePath
        prefs.edit().putString("hddPath", newPath).apply()
        Log.i(tag, "recreateHdd: done, size=${target.length()}")

        runOnUiThread {
          Toast.makeText(this, getString(R.string.settings_recreate_hdd_success), Toast.LENGTH_SHORT).show()
        }
      } catch (e: Exception) {
        Log.e(tag, "recreateHdd failed", e)
        runOnUiThread {
          Toast.makeText(this, getString(R.string.settings_recreate_hdd_failed, e.message), Toast.LENGTH_LONG).show()
        }
      }
    }.start()
  }

  private fun exportHddToUri(uri: Uri) {
    val hddPath = resolveHddPath()
    if (hddPath == null) {
      Toast.makeText(this, getString(R.string.settings_export_hdd_no_file), Toast.LENGTH_LONG).show()
      return
    }
    Toast.makeText(this, getString(R.string.settings_export_hdd_copying), Toast.LENGTH_SHORT).show()
    Thread {
      try {
        val source = File(hddPath)
        contentResolver.openOutputStream(uri)?.use { output ->
          source.inputStream().use { input ->
            input.copyTo(output)
          }
        } ?: throw IOException("Unable to open output")
        runOnUiThread {
          Toast.makeText(this, getString(R.string.settings_export_hdd_success), Toast.LENGTH_SHORT).show()
        }
      } catch (e: Exception) {
        Log.e("SettingsActivity", "HDD export failed", e)
        runOnUiThread {
          Toast.makeText(this, getString(R.string.settings_export_hdd_failed, e.message), Toast.LENGTH_LONG).show()
        }
      }
    }.start()
  }

  private fun updateMcpxPath() {
    val path = prefs.getString("mcpxPath", null)
    mcpxPathText.text = getString(R.string.settings_mcpx_label,
      if (path != null && File(path).isFile) path else getString(R.string.settings_file_not_set))
  }

  private fun updateFlashPath() {
    val path = prefs.getString("flashPath", null)
    flashPathText.text = getString(R.string.settings_flash_label,
      if (path != null && File(path).isFile) path else getString(R.string.settings_file_not_set))
  }

  private fun updateHddPath() {
    val path = prefs.getString("hddPath", null)
    hddPathText.text = getString(R.string.settings_hdd_label,
      if (path != null && File(path).isFile) path else getString(R.string.settings_file_not_set))
  }

  private fun updateGamesFolderPath() {
    val uriStr = prefs.getString("gamesFolderUri", null)
    val label = if (uriStr != null) {
      val uri = Uri.parse(uriStr)
      DocumentFile.fromTreeUri(this, uri)?.name ?: uri.toString()
    } else {
      getString(R.string.settings_file_not_set)
    }
    gamesFolderPathText.text = getString(R.string.settings_games_folder_label, label)
  }

  private fun updateTextureDumpPath() {
    val txtPath = findViewById<TextView>(R.id.txt_texture_dump_path) ?: return
    val uriStr = prefs.getString("textureDumpFolderUri", null)
    if (uriStr != null) {
      val uri = Uri.parse(uriStr)
      val name = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: uri.toString()
      txtPath.text = "Dump folder: $name"
    } else {
      txtPath.text = "No folder selected"
    }
  }

  private fun resolveTextureDumpRealPath(): String? {
    val uriStr = prefs.getString("textureDumpFolderUri", null) ?: return null
    val uri = Uri.parse(uriStr)
    val docUri = androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
    if (docUri == null || !docUri.canWrite()) return null

    // Try to extract filesystem path from the URI
    // SAF tree URIs for primary storage typically encode the path
    val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
    if (docId.startsWith("primary:")) {
      val relPath = docId.removePrefix("primary:")
      val realPath = android.os.Environment.getExternalStorageDirectory().absolutePath + "/" + relPath
      val dir = java.io.File(realPath)
      if (dir.isDirectory || dir.mkdirs()) return realPath
    }

    // Fallback: use app-private directory
    val fallback = java.io.File(getExternalFilesDir(null), "texture_dump")
    fallback.mkdirs()
    return fallback.absolutePath
  }

  private fun applyTextureDumpPath() {
    val path = resolveTextureDumpRealPath()
    if (path != null) {
      try { nativeSetTextureDumpPath(path) } catch (_: Throwable) {}
    }
  }

  private fun updateTextureReplacePath() {
    val txtPath = findViewById<TextView>(R.id.txt_texture_replace_path) ?: return
    val uriStr = prefs.getString("textureReplaceFolderUri", null)
    if (uriStr != null) {
      val uri = Uri.parse(uriStr)
      val name = DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment ?: uri.toString()
      txtPath.text = "Texture pack folder: $name"
    } else {
      txtPath.text = "No folder selected"
    }
  }

  private fun resolveTextureReplaceRealPath(): String? {
    val uriStr = prefs.getString("textureReplaceFolderUri", null) ?: return null
    val uri = Uri.parse(uriStr)
    val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
    if (docId.startsWith("primary:")) {
      val relPath = docId.removePrefix("primary:")
      val realPath = android.os.Environment.getExternalStorageDirectory().absolutePath + "/" + relPath
      val dir = java.io.File(realPath)
      if (dir.isDirectory) return realPath
    }
    return null
  }

  private fun applyTextureReplacePath() {
    val path = resolveTextureReplaceRealPath()
    if (path != null) {
      try { nativeSetTextureReplacePath(path) } catch (_: Throwable) {}
    }
  }

  private fun copySystemFile(uri: Uri, destName: String, pathKey: String, uriKey: String, onDone: () -> Unit) {
    try {
      contentResolver.takePersistableUriPermission(uri,
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (_: SecurityException) {}
    prefs.edit().putString(uriKey, uri.toString()).apply()
    Toast.makeText(this, getString(R.string.settings_file_copying), Toast.LENGTH_SHORT).show()
    Thread {
      val path = copyUriToAppStorage(uri, destName)
      runOnUiThread {
        if (path != null) {
          prefs.edit().putString(pathKey, path).apply()
          Toast.makeText(this, getString(R.string.settings_file_copied), Toast.LENGTH_SHORT).show()
        } else {
          Toast.makeText(this, getString(R.string.settings_file_copy_failed), Toast.LENGTH_LONG).show()
        }
        onDone()
      }
    }.start()
  }

  private fun copyUriToAppStorage(uri: Uri, destName: String): String? {
    val base = getExternalFilesDir(null) ?: filesDir
    val dir = File(base, "x1box")
    if (!dir.exists() && !dir.mkdirs()) {
      Log.e("SettingsActivity", "Failed to create ${dir.absolutePath}")
      return null
    }
    val target = File(dir, destName)
    return try {
      val bytesCopied = contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(target).use { output -> input.copyTo(output) }
      } ?: return null
      if (bytesCopied == 0L) {
        target.delete()
        return null
      }
      target.absolutePath
    } catch (e: IOException) {
      Log.e("SettingsActivity", "Copy failed for $destName", e)
      target.delete()
      null
    }
  }

  private fun resolveEepromFile(): File {
    val base = getExternalFilesDir(null) ?: filesDir
    return File(File(base, "x1box"), "eeprom.bin")
  }

  private fun setupEepromEditor() {
    val languageLabels = eepromLanguageOptions.map { getString(it.labelRes) }
    val videoLabels = eepromVideoOptions.map { getString(it.labelRes) }
    val aspectRatioLabels = eepromAspectRatioOptions.map { getString(it.labelRes) }
    val refreshRateLabels = eepromRefreshRateOptions.map { getString(it.labelRes) }

    dropdownEepromLanguage.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, languageLabels))
    dropdownEepromVideoStandard.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, videoLabels))
    dropdownEepromAspectRatio.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, aspectRatioLabels))
    dropdownEepromRefreshRate.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, refreshRateLabels))

    dropdownEepromLanguage.setOnItemClickListener { _, _, position, _ ->
      selectedEepromLanguage = eepromLanguageOptions[position].value
    }
    dropdownEepromVideoStandard.setOnItemClickListener { _, _, position, _ ->
      selectedEepromVideoStandard = eepromVideoOptions[position].value
    }
    dropdownEepromAspectRatio.setOnItemClickListener { _, _, position, _ ->
      selectedEepromAspectRatio = eepromAspectRatioOptions[position].value
    }
    dropdownEepromRefreshRate.setOnItemClickListener { _, _, position, _ ->
      selectedEepromRefreshRate = eepromRefreshRateOptions[position].value
    }

    val eepromFile = resolveEepromFile()
    if (!eepromFile.isFile) {
      eepromEditable = false
      eepromMissing = true
      setEepromEditorEnabled(false)
      setEepromDefaults()
      tvEepromStatus.text = getString(R.string.settings_eeprom_status_missing, eepromFile.absolutePath)
      return
    }

    try {
      val snapshot = XboxEepromEditor.load(eepromFile)
      eepromEditable = true
      eepromMissing = false
      eepromError = false
      setEepromEditorEnabled(true)
      setEepromLanguageSelection(snapshot.language)
      setEepromVideoSelection(snapshot.videoStandard)
      setEepromVideoSettingsSelection(snapshot.videoSettings)

      val hasUnknownValues =
        snapshot.rawLanguage != snapshot.language.id ||
        snapshot.rawVideoStandard != snapshot.videoStandard.id ||
        snapshot.hasManagedVideoSettingsMismatch
      tvEepromStatus.text = if (hasUnknownValues) {
        getString(R.string.settings_eeprom_status_unknown, eepromFile.absolutePath)
      } else {
        getString(R.string.settings_eeprom_status_ready, eepromFile.absolutePath)
      }
    } catch (_: IllegalArgumentException) {
      eepromEditable = false
      eepromError = true
      setEepromEditorEnabled(false)
      setEepromDefaults()
      tvEepromStatus.text = getString(R.string.settings_eeprom_status_invalid, eepromFile.absolutePath)
    } catch (_: Exception) {
      eepromEditable = false
      eepromError = true
      setEepromEditorEnabled(false)
      setEepromDefaults()
      tvEepromStatus.text = getString(R.string.settings_eeprom_status_error, eepromFile.absolutePath)
    }
  }

  private fun applyEepromEdits() {
    if (eepromMissing) {
      Toast.makeText(this, getString(R.string.settings_eeprom_status_missing, resolveEepromFile().absolutePath), Toast.LENGTH_LONG).show()
      return
    }
    if (eepromError || !eepromEditable) {
      Toast.makeText(this, getString(R.string.settings_eeprom_save_failed), Toast.LENGTH_LONG).show()
      return
    }
    try {
      val changed = XboxEepromEditor.apply(
        resolveEepromFile(),
        selectedEepromLanguage,
        selectedEepromVideoStandard,
        XboxEepromEditor.VideoSettings(
          allow480p = switchEeprom480p.isChecked,
          allow720p = switchEeprom720p.isChecked,
          allow1080i = switchEeprom1080i.isChecked,
          aspectRatio = selectedEepromAspectRatio,
          refreshRate = selectedEepromRefreshRate,
        ),
      )
      Toast.makeText(this,
        if (changed) getString(R.string.settings_eeprom_saved)
        else getString(R.string.settings_eeprom_no_changes),
        Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
      Log.e("SettingsActivity", "EEPROM save failed", e)
      Toast.makeText(this, getString(R.string.settings_eeprom_save_failed), Toast.LENGTH_LONG).show()
    }
  }

  private fun setEepromEditorEnabled(enabled: Boolean) {
    inputEepromLanguage.isEnabled = enabled
    inputEepromVideoStandard.isEnabled = enabled
    inputEepromAspectRatio.isEnabled = enabled
    inputEepromRefreshRate.isEnabled = enabled
    dropdownEepromLanguage.isEnabled = enabled
    dropdownEepromVideoStandard.isEnabled = enabled
    dropdownEepromAspectRatio.isEnabled = enabled
    dropdownEepromRefreshRate.isEnabled = enabled
    switchEeprom480p.isEnabled = enabled
    switchEeprom720p.isEnabled = enabled
    switchEeprom1080i.isEnabled = enabled
  }

  private fun setEepromDefaults() {
    setEepromLanguageSelection(selectedEepromLanguage)
    setEepromVideoSelection(selectedEepromVideoStandard)
    setEepromVideoSettingsSelection(XboxEepromEditor.VideoSettings(
      allow480p = false, allow720p = false, allow1080i = false,
      aspectRatio = selectedEepromAspectRatio, refreshRate = selectedEepromRefreshRate,
    ))
  }

  private fun setEepromLanguageSelection(language: XboxEepromEditor.Language) {
    selectedEepromLanguage = language
    val option = eepromLanguageOptions.firstOrNull { it.value == language } ?: eepromLanguageOptions.first()
    dropdownEepromLanguage.setText(getString(option.labelRes), false)
  }

  private fun setEepromVideoSelection(video: XboxEepromEditor.VideoStandard) {
    selectedEepromVideoStandard = video
    val option = eepromVideoOptions.firstOrNull { it.value == video } ?: eepromVideoOptions.first()
    dropdownEepromVideoStandard.setText(getString(option.labelRes), false)
  }

  private fun setEepromVideoSettingsSelection(videoSettings: XboxEepromEditor.VideoSettings) {
    switchEeprom480p.isChecked = videoSettings.allow480p
    switchEeprom720p.isChecked = videoSettings.allow720p
    switchEeprom1080i.isChecked = videoSettings.allow1080i
    selectedEepromAspectRatio = videoSettings.aspectRatio
    val arOption = eepromAspectRatioOptions.firstOrNull { it.value == videoSettings.aspectRatio } ?: eepromAspectRatioOptions.first()
    dropdownEepromAspectRatio.setText(getString(arOption.labelRes), false)
    selectedEepromRefreshRate = videoSettings.refreshRate
    val rrOption = eepromRefreshRateOptions.firstOrNull { it.value == videoSettings.refreshRate } ?: eepromRefreshRateOptions.first()
    dropdownEepromRefreshRate.setText(getString(rrOption.labelRes), false)
  }

  private external fun nativeGetFpSafe(): Boolean
  private external fun nativeSetFpSafe(enable: Boolean)
  private external fun nativeGetFpJit(): Boolean
  private external fun nativeSetFpJit(enable: Boolean)
  private external fun nativeGetFastFences(): Boolean
  private external fun nativeSetFastFences(enable: Boolean)
  private external fun nativeGetDrawReorder(): Boolean
  private external fun nativeSetDrawReorder(enable: Boolean)
  private external fun nativeGetDrawMerge(): Boolean
  private external fun nativeSetDrawMerge(enable: Boolean)
  private external fun nativeGetAsyncCompile(): Boolean
  private external fun nativeSetAsyncCompile(enable: Boolean)
  private external fun nativeGetFrameSkip(): Boolean
  private external fun nativeSetFrameSkip(enable: Boolean)
  private external fun nativeGetSubmitFrames(): Int
  private external fun nativeSetSubmitFrames(count: Int)
  private external fun nativeGetTier1Threshold(): Int
  private external fun nativeSetTier1Threshold(value: Int)
  private external fun nativeSetTextureDumpEnabled(enable: Boolean)
  private external fun nativeGetTextureDumpEnabled(): Boolean
  private external fun nativeSetTextureDumpPath(path: String)
  private external fun nativeResetTextureDumpCache()
  private external fun nativeSetTextureReplaceEnabled(enable: Boolean)
  private external fun nativeGetTextureReplaceEnabled(): Boolean
  private external fun nativeSetTextureReplacePath(path: String)
  private external fun nativeReloadTextureReplace()

  companion object {
    private const val FATX_SUPERBLOCK_SIZE = 4096
    private const val FATX_MAGIC = 0x46415458 // "FATX" read as big-endian int
    private const val QCOW2_MAGIC = 0x514649fb.toInt() // "QFI\xfb"
    private const val WIPE_SIZE = 1 * 1024 * 1024L
    private const val PREF_INSIGNIA_SETUP_URI = "setting_insignia_setup_assistant_uri"
    private const val PREF_INSIGNIA_SETUP_NAME = "setting_insignia_setup_assistant_name"
    private const val INSIGNIA_SIGN_UP_URL = "https://insignia.live/"
    private const val INSIGNIA_GUIDE_URL = "https://insignia.live/guide/connect"

    private val STANDARD_CACHE_OFFSETS = longArrayOf(
      0x00080000L, // X
      0x2EE80000L, // Y
      0x5DC80000L, // Z
    )
  }

  private fun clearHddCachePartitions(hddPath: String) {
    val tag = "XemuHddCache"
    val file = File(hddPath)
    val imageLen = file.length()
    Log.i(tag, "clearHddCachePartitions: path=$hddPath length=$imageLen " +
      "(0x${imageLen.toString(16)}) canWrite=${file.canWrite()}")
    require(file.exists() && imageLen > 0) { "HDD image missing or empty" }

    RandomAccessFile(file, "rwd").use { raf ->
      raf.seek(0)
      val fileMagic = raf.readInt()

      if (fileMagic == QCOW2_MAGIC) {
        Log.i(tag, "  format: QCOW2")
        clearCacheQcow2(raf, tag)
      } else {
        Log.i(tag, "  format: raw")
        clearCacheRaw(raf, imageLen, tag)
      }
    }
  }

  private fun clearCacheRaw(raf: RandomAccessFile, imageLen: Long, tag: String) {
    var cleared = 0
    for (offset in STANDARD_CACHE_OFFSETS) {
      if (offset + FATX_SUPERBLOCK_SIZE > imageLen) {
        Log.i(tag, "  raw@0x${offset.toString(16)}: beyond image, skip")
        continue
      }
      raf.seek(offset)
      val sig = raf.readInt()
      if (sig != FATX_MAGIC) {
        Log.i(tag, "  raw@0x${offset.toString(16)}: no FATX (0x${sig.toString(16)}), skip")
        continue
      }
      Log.i(tag, "  raw@0x${offset.toString(16)}: FATX found, reformatting")
      val wipeLen = minOf(WIPE_SIZE, imageLen - offset).toInt()
      raf.seek(offset)
      raf.write(ByteArray(wipeLen))
      writeFatxSuperblock(raf, offset)
      cleared++
    }
    if (cleared == 0) {
      throw IllegalStateException("No cache partitions found in raw image ($imageLen bytes)")
    }
    Log.i(tag, "  raw: $cleared partition(s) cleared")
  }

  private fun clearCacheQcow2(raf: RandomAccessFile, tag: String) {
    raf.seek(4)
    val version = raf.readInt()
    raf.seek(20)
    val clusterBits = raf.readInt()
    val virtualSize = raf.readLong()
    raf.seek(36)
    val l1Size = raf.readInt()
    val l1TableOffset = raf.readLong()
    val clusterSize = 1 shl clusterBits
    val l2Bits = clusterBits - 3

    Log.i(tag, "  qcow2: v$version clusterBits=$clusterBits clusterSize=$clusterSize " +
      "virtualSize=0x${virtualSize.toString(16)} ($virtualSize) " +
      "l1Size=$l1Size l1TableOff=0x${l1TableOffset.toString(16)}")

    var cleared = 0
    for (partOff in STANDARD_CACHE_OFFSETS) {
      if (partOff >= virtualSize) {
        Log.i(tag, "  qcow2@0x${partOff.toString(16)}: beyond virtual disk, skip")
        continue
      }

      val sigPhys = qcow2Resolve(raf, partOff, clusterBits, l2Bits, l1Size, l1TableOffset)
      if (sigPhys < 0) {
        Log.i(tag, "  qcow2@0x${partOff.toString(16)}: unallocated, skip")
        continue
      }
      raf.seek(sigPhys)
      val sig = raf.readInt()
      if (sig != FATX_MAGIC) {
        Log.i(tag, "  qcow2@0x${partOff.toString(16)}: no FATX (0x${sig.toString(16)}), skip")
        continue
      }

      Log.i(tag, "  qcow2@0x${partOff.toString(16)}: FATX found, reformatting")

      val wipeEnd = minOf(partOff + WIPE_SIZE, virtualSize)
      var vOff = partOff
      while (vOff < wipeEnd) {
        val inCluster = (vOff and (clusterSize - 1).toLong()).toInt()
        val chunk = minOf((clusterSize - inCluster).toLong(), wipeEnd - vOff).toInt()
        val phys = qcow2Resolve(raf, vOff, clusterBits, l2Bits, l1Size, l1TableOffset)
        if (phys >= 0) {
          raf.seek(phys)
          raf.write(ByteArray(chunk))
        }
        vOff += chunk
      }

      val sbPhys = qcow2Resolve(raf, partOff, clusterBits, l2Bits, l1Size, l1TableOffset)
      if (sbPhys >= 0) {
        writeFatxSuperblockAt(raf, sbPhys)
        val fatVirt = partOff + FATX_SUPERBLOCK_SIZE
        val fatPhys = qcow2Resolve(raf, fatVirt, clusterBits, l2Bits, l1Size, l1TableOffset)
        if (fatPhys >= 0) {
          raf.seek(fatPhys)
          val fatEntry = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
          fatEntry.putInt(0xFFFFFFF8.toInt())
          raf.write(fatEntry.array())
        }
      }
      cleared++
    }

    if (cleared == 0) {
      throw IllegalStateException(
        "No cache partitions found in QCOW2 image (virtualSize=0x${virtualSize.toString(16)})")
    }
    Log.i(tag, "  qcow2: $cleared partition(s) cleared")
  }

  private fun qcow2Resolve(
    raf: RandomAccessFile, virtOff: Long,
    clusterBits: Int, l2Bits: Int, l1Size: Int, l1TableOffset: Long
  ): Long {
    val clusterSize = 1 shl clusterBits
    val l1Idx = (virtOff ushr (clusterBits + l2Bits)).toInt()
    val l2Idx = ((virtOff ushr clusterBits) and ((1 shl l2Bits) - 1).toLong()).toInt()
    val inCluster = (virtOff and (clusterSize - 1).toLong()).toInt()
    if (l1Idx >= l1Size) return -1

    raf.seek(l1TableOffset + l1Idx.toLong() * 8)
    val l1Entry = raf.readLong()
    val l2Off = l1Entry and 0x00fffffffffffe00L
    if (l2Off == 0L) return -1

    raf.seek(l2Off + l2Idx.toLong() * 8)
    val l2Entry = raf.readLong()
    val dataOff = l2Entry and 0x00fffffffffffe00L
    if (dataOff == 0L) return -1

    return dataOff + inCluster
  }

  private fun writeFatxSuperblock(raf: RandomAccessFile, offset: Long) {
    raf.seek(offset)
    writeFatxSuperblockAt(raf, offset)
    if (raf.filePointer + 4 <= raf.length()) {
      val fatEntry = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
      fatEntry.putInt(0xFFFFFFF8.toInt())
      raf.write(fatEntry.array())
    }
  }

  private fun writeFatxSuperblockAt(raf: RandomAccessFile, physOffset: Long) {
    val sb = ByteBuffer.allocate(FATX_SUPERBLOCK_SIZE).order(ByteOrder.BIG_ENDIAN)
    sb.putInt(FATX_MAGIC)
    sb.order(ByteOrder.LITTLE_ENDIAN)
    sb.putInt((System.nanoTime() and 0xFFFFFFFFL).toInt())
    sb.putInt(32) // sectors_per_cluster
    sb.putInt(1)  // root_cluster
    sb.putShort(0)
    val remaining = FATX_SUPERBLOCK_SIZE - sb.position()
    for (i in 0 until remaining) sb.put(0xFF.toByte())
    raf.seek(physOffset)
    raf.write(sb.array())
  }

  // ── Resolve HDD file (wraps resolveHddPath) ────────────────────────────

  private fun resolveHddFile(): File? {
    val path = resolveHddPath() ?: return null
    val file = File(path)
    return file.takeIf { it.isFile }
  }

  // ── Online / Insignia ──────────────────────────────────────────────────

  private fun setupOnlineSection() {
    switchNetworkEnable.isChecked = prefs.getBoolean("setting_network_enable", false)
    switchNetworkEnable.setOnCheckedChangeListener { _, checked ->
      prefs.edit().putBoolean("setting_network_enable", checked).apply()
    }
    btnInsigniaGuide.setOnClickListener {
      openExternalLink(INSIGNIA_GUIDE_URL)
    }
    btnInsigniaSignUp.setOnClickListener {
      openExternalLink(INSIGNIA_SIGN_UP_URL)
    }
    btnPrepareInsignia.setOnClickListener {
      prepareInsigniaNetworking()
    }
    btnRegisterInsignia.setOnClickListener {
      showInsigniaSetupAssistantPrompt()
    }
  }

  private fun refreshInsigniaStatus() {
    val hddFile = resolveHddFile()
    val eepromFile = resolveEepromFile()
    val setupUri = resolveInsigniaSetupAssistantUri()
    val setupName = resolveInsigniaSetupAssistantName()
    val setupReady = setupUri != null && hasPersistedReadPermission(setupUri)

    tvInsigniaStatus.text = getString(R.string.settings_insignia_status_checking)

    Thread {
      var dashboardStatus: XboxInsigniaHelper.DashboardStatus? = null
      var dashboardError: String? = null

      if (hddFile != null) {
        try {
          dashboardStatus = XboxInsigniaHelper.inspectDashboard(hddFile)
        } catch (error: Exception) {
          dashboardError = error.message ?: error.javaClass.simpleName
        }
      }

      val snapshot = InsigniaStatusSnapshot(
        hasLocalHdd = hddFile != null,
        hasEeprom = eepromFile.isFile,
        dashboardStatus = dashboardStatus,
        dashboardError = dashboardError,
        setupAssistantName = setupName,
        setupAssistantReady = setupReady,
      )

      runOnUiThread {
        if (isFinishing || isDestroyed) {
          return@runOnUiThread
        }
        tvInsigniaStatus.text = buildInsigniaStatusText(snapshot)
      }
    }.start()
  }

  private fun buildInsigniaStatusText(snapshot: InsigniaStatusSnapshot): String {
    val dashboardLine = when {
      !snapshot.hasLocalHdd ->
        getString(R.string.settings_insignia_status_dashboard_unavailable)
      snapshot.dashboardError != null ->
        getString(R.string.settings_insignia_status_dashboard_error, snapshot.dashboardError)
      snapshot.dashboardStatus?.looksRetailDashboardInstalled == true ->
        getString(R.string.settings_insignia_status_dashboard_ready)
      snapshot.dashboardStatus?.hasAnyRetailDashboardFiles == true ->
        getString(R.string.settings_insignia_status_dashboard_partial)
      else ->
        getString(R.string.settings_insignia_status_dashboard_missing)
    }

    val eepromLine = if (snapshot.hasEeprom) {
      getString(R.string.settings_insignia_status_eeprom_ready)
    } else {
      getString(R.string.settings_insignia_status_eeprom_missing)
    }

    val setupLine = when {
      snapshot.setupAssistantReady ->
        getString(
          R.string.settings_insignia_status_setup_selected,
          snapshot.setupAssistantName ?: getString(R.string.settings_insignia_setup_source_unknown),
        )
      snapshot.setupAssistantName != null ->
        getString(R.string.settings_insignia_status_setup_inaccessible, snapshot.setupAssistantName)
      else ->
        getString(R.string.settings_insignia_status_setup_missing)
    }

    return listOf(
      getString(
        R.string.settings_insignia_status_dns,
        XboxInsigniaHelper.PRIMARY_DNS,
        XboxInsigniaHelper.SECONDARY_DNS,
      ),
      dashboardLine,
      eepromLine,
      setupLine,
    ).joinToString("\n")
  }

  private fun prepareInsigniaNetworking() {
    if (isPreparingInsignia || isInitializingHdd || isImportingDashboard) {
      return
    }

    val hddFile = resolveHddFile()
    if (hddFile == null) {
      Toast.makeText(this, R.string.settings_insignia_prepare_no_hdd, Toast.LENGTH_LONG).show()
      refreshInsigniaStatus()
      return
    }

    val eepromFile = resolveEepromFile()
    if (!eepromFile.isFile) {
      Toast.makeText(this, R.string.settings_insignia_prepare_no_eeprom, Toast.LENGTH_LONG).show()
      refreshInsigniaStatus()
      return
    }

    switchNetworkEnable.isChecked = true
    prefs.edit().putBoolean("setting_network_enable", true).apply()

    isPreparingInsignia = true
    Toast.makeText(this, R.string.settings_insignia_prepare_working, Toast.LENGTH_SHORT).show()

    Thread {
      val result = runCatching {
        XboxInsigniaHelper.applyConfigSectorDns(hddFile)
        XboxEepromEditor.applyXboxLiveDns(eepromFile, XboxInsigniaHelper.primaryDnsBytes())
        runCatching { XboxInsigniaHelper.inspectDashboard(hddFile) }.getOrNull()
      }

      runOnUiThread {
        isPreparingInsignia = false
        if (isFinishing || isDestroyed) {
          return@runOnUiThread
        }

        result.onSuccess { dashboardStatus ->
          refreshInsigniaStatus()
          val messageRes = if (dashboardStatus?.looksRetailDashboardInstalled == false) {
            R.string.settings_insignia_prepare_success_missing_dashboard
          } else {
            R.string.settings_insignia_prepare_success
          }
          Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
        }.onFailure { error ->
          Toast.makeText(
            this,
            getString(
              R.string.settings_insignia_prepare_failed,
              error.message ?: error.javaClass.simpleName,
            ),
            Toast.LENGTH_LONG,
          ).show()
          refreshInsigniaStatus()
        }
      }
    }.start()
  }

  private fun showInsigniaSetupAssistantPrompt() {
    val setupUri = resolveInsigniaSetupAssistantUri()
    if (setupUri == null || !hasPersistedReadPermission(setupUri)) {
      if (setupUri != null) {
        prefs.edit()
          .remove(PREF_INSIGNIA_SETUP_URI)
          .remove(PREF_INSIGNIA_SETUP_NAME)
          .apply()
      }
      refreshInsigniaStatus()
      Toast.makeText(this, R.string.settings_insignia_register_pick_prompt, Toast.LENGTH_SHORT).show()
      pickInsigniaSetupAssistant.launch(arrayOf("*/*"))
      return
    }

    val setupName = resolveInsigniaSetupAssistantName()
      ?: getString(R.string.settings_insignia_setup_source_unknown)
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.settings_insignia_register_title)
      .setMessage(getString(R.string.settings_insignia_register_message, setupName))
      .setPositiveButton(R.string.settings_insignia_register_boot_action) { _, _ ->
        launchInsigniaSetupAssistant(setupUri)
      }
      .setNeutralButton(R.string.settings_insignia_register_choose_new) { _, _ ->
        pickInsigniaSetupAssistant.launch(arrayOf("*/*"))
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun launchInsigniaSetupAssistant(uri: Uri) {
    persistUriPermission(uri)
    switchNetworkEnable.isChecked = true
    val launchEditor = prefs.edit()
    PerGameSettingsManager.applyRuntimeOverridesToEditor(
      context = this,
      editor = launchEditor,
      relativePath = null,
    )
    launchEditor
      .putBoolean("setting_network_enable", true)
      .putString("dvdUri", uri.toString())
      .remove("dvdPath")
      .putBoolean("skip_game_picker", false)
      .commit()

    startActivity(Intent(this, MainActivity::class.java))
  }

  private fun resolveInsigniaSetupAssistantUri(): Uri? {
    return prefs.getString(PREF_INSIGNIA_SETUP_URI, null)?.let(Uri::parse)
  }

  private fun resolveInsigniaSetupAssistantName(): String? {
    return prefs.getString(PREF_INSIGNIA_SETUP_NAME, null)
  }

  private fun openExternalLink(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
      addCategory(Intent.CATEGORY_BROWSABLE)
    }
    try {
      startActivity(intent)
    } catch (_: Exception) {
      Toast.makeText(this, getString(R.string.library_about_open_failed), Toast.LENGTH_SHORT).show()
    }
  }

  // ── HDD Tools ──────────────────────────────────────────────────────────

  private fun setupHddToolsSection() {
    btnInitializeRetailHdd.setOnClickListener {
      showInitializeHddLayoutPicker()
    }
  }

  private fun refreshHddToolsState() {
    val hddFile = resolveHddFile()
    if (hddFile == null) {
      tvHddToolsStatus.text = getString(R.string.settings_hdd_status_missing)
      btnInitializeRetailHdd.isEnabled = false
      return
    }

    val inspection = runCatching { XboxHddFormatter.inspect(hddFile) }.getOrElse { error ->
      tvHddToolsStatus.text = getString(
        R.string.settings_hdd_status_error,
        error.message ?: hddFile.absolutePath,
      )
      btnInitializeRetailHdd.isEnabled = false
      return
    }

    val sizeLabel = Formatter.formatFileSize(this, inspection.totalBytes)
    val formatLabel = getString(hddFormatLabelRes(inspection.format))
    tvHddToolsStatus.text = when {
      inspection.totalBytes < XboxHddFormatter.MINIMUM_RETAIL_DISK_BYTES -> getString(
        R.string.settings_hdd_status_too_small,
        formatLabel,
        sizeLabel,
        hddFile.absolutePath,
      )
      else -> getString(
        R.string.settings_hdd_status_ready,
        formatLabel,
        sizeLabel,
        hddFile.absolutePath,
      )
    }
    btnInitializeRetailHdd.isEnabled = !isInitializingHdd && XboxHddFormatter.supportedLayouts(inspection).isNotEmpty()
  }

  private fun showInitializeHddLayoutPicker() {
    val hddFile = resolveHddFile()
    if (hddFile == null) {
      refreshHddToolsState()
      return
    }

    val inspection = runCatching { XboxHddFormatter.inspect(hddFile) }.getOrElse { error ->
      tvHddToolsStatus.text = getString(
        R.string.settings_hdd_status_error,
        error.message ?: hddFile.absolutePath,
      )
      btnInitializeRetailHdd.isEnabled = false
      return
    }

    if (!inspection.supportsRetailFormat) {
      refreshHddToolsState()
      return
    }

    val supportedLayouts = XboxHddFormatter.supportedLayouts(inspection).toSet()
    if (supportedLayouts.isEmpty()) {
      refreshHddToolsState()
      return
    }

    val allLayouts = XboxHddFormatter.Layout.entries
    val labels = allLayouts
      .map { layout ->
        val label = getString(hddLayoutLabelRes(layout))
        val availability = XboxHddFormatter.availabilityFor(inspection, layout)
        if (availability == XboxHddFormatter.LayoutAvailability.AVAILABLE) {
          label
        } else {
          getString(
            R.string.settings_hdd_layout_unavailable_format,
            label,
            getString(hddLayoutUnavailableReasonRes(availability)),
          )
        }
      }
      .toTypedArray()
    val dp = resources.displayMetrics.density
    lateinit var hddDialog: androidx.appcompat.app.AlertDialog

    val buttonList = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), 0)
      labels.forEachIndexed { i, label ->
        addView(MaterialButton(this@SettingsActivity, null,
          com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
          text = label
          isAllCaps = false
          layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
          ).also { lp -> lp.bottomMargin = (8 * dp).toInt() }
          setOnClickListener {
            hddDialog.dismiss()
            val layout = allLayouts[i]
            val availability = XboxHddFormatter.availabilityFor(inspection, layout)
            if (availability == XboxHddFormatter.LayoutAvailability.AVAILABLE) {
              showInitializeHddConfirmation(hddFile, layout)
            } else {
              MaterialAlertDialogBuilder(this@SettingsActivity)
                .setTitle(R.string.settings_hdd_layout_unavailable_title)
                .setMessage(getString(hddLayoutUnavailableReasonRes(availability)))
                .setPositiveButton(android.R.string.ok, null)
                .show()
            }
          }
        })
      }
    }

    hddDialog = MaterialAlertDialogBuilder(this)
      .setTitle(R.string.settings_hdd_layout_pick_title)
      .setView(buttonList)
      .setNegativeButton(android.R.string.cancel, null)
      .create()
    hddDialog.show()
  }

  private fun showInitializeHddConfirmation(
    hddFile: File,
    layout: XboxHddFormatter.Layout,
  ) {
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.settings_hdd_init_title)
      .setMessage(
        getString(
          R.string.settings_hdd_init_message,
          getString(hddLayoutLabelRes(layout)),
          getString(hddLayoutSummaryRes(layout)),
          hddFile.absolutePath,
        )
      )
      .setPositiveButton(R.string.settings_hdd_init_action) { _, _ ->
        initializeHddLayout(hddFile, layout)
      }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun initializeHddLayout(
    hddFile: File,
    layout: XboxHddFormatter.Layout,
  ) {
    if (isInitializingHdd) {
      return
    }

    isInitializingHdd = true
    btnInitializeRetailHdd.isEnabled = false
    Toast.makeText(this, R.string.settings_hdd_init_working, Toast.LENGTH_SHORT).show()

    Thread {
      val result = runCatching {
        XboxHddFormatter.initialize(hddFile, layout)
      }

      runOnUiThread {
        isInitializingHdd = false
        refreshHddToolsState()
        refreshInsigniaStatus()
        result.onSuccess {
          Toast.makeText(this, R.string.settings_hdd_init_success, Toast.LENGTH_SHORT).show()
        }.onFailure { error ->
          Toast.makeText(
            this,
            getString(
              R.string.settings_hdd_init_failed,
              error.message ?: hddFile.absolutePath,
            ),
            Toast.LENGTH_LONG,
          ).show()
        }
      }
    }.start()
  }

  private fun hddFormatLabelRes(format: XboxHddFormatter.ImageFormat): Int {
    return when (format) {
      XboxHddFormatter.ImageFormat.RAW -> R.string.settings_hdd_format_raw
      XboxHddFormatter.ImageFormat.QCOW2 -> R.string.settings_hdd_format_qcow2
    }
  }

  private fun hddLayoutLabelRes(layout: XboxHddFormatter.Layout): Int {
    return when (layout) {
      XboxHddFormatter.Layout.RETAIL -> R.string.settings_hdd_layout_retail
      XboxHddFormatter.Layout.RETAIL_PLUS_F -> R.string.settings_hdd_layout_retail_f
      XboxHddFormatter.Layout.RETAIL_PLUS_F_G -> R.string.settings_hdd_layout_retail_f_g
    }
  }

  private fun hddLayoutSummaryRes(layout: XboxHddFormatter.Layout): Int {
    return when (layout) {
      XboxHddFormatter.Layout.RETAIL -> R.string.settings_hdd_layout_summary_retail
      XboxHddFormatter.Layout.RETAIL_PLUS_F -> R.string.settings_hdd_layout_summary_retail_f
      XboxHddFormatter.Layout.RETAIL_PLUS_F_G -> R.string.settings_hdd_layout_summary_retail_f_g
    }
  }

  private fun hddLayoutUnavailableReasonRes(
    availability: XboxHddFormatter.LayoutAvailability,
  ): Int {
    return when (availability) {
      XboxHddFormatter.LayoutAvailability.AVAILABLE ->
        R.string.settings_hdd_layout_unavailable_not_enough_space
      XboxHddFormatter.LayoutAvailability.NO_EXTENDED_SPACE ->
        R.string.settings_hdd_layout_unavailable_no_extended_space
      XboxHddFormatter.LayoutAvailability.NEEDS_STANDARD_G_BOUNDARY ->
        R.string.settings_hdd_layout_unavailable_needs_standard_g_boundary
      XboxHddFormatter.LayoutAvailability.NOT_ENOUGH_SPACE ->
        R.string.settings_hdd_layout_unavailable_not_enough_space
    }
  }

  // ── Dashboard Import ───────────────────────────────────────────────────

  private fun setupDashboardImportSection() {
    btnDashboardImportZip.setOnClickListener {
      val hddFile = resolveHddFile()
      if (hddFile == null) {
        Toast.makeText(this, R.string.settings_hdd_status_missing, Toast.LENGTH_LONG).show()
        return@setOnClickListener
      }
      if (isImportingDashboard) return@setOnClickListener
      pickDashboardZip.launch(arrayOf("application/zip", "application/octet-stream"))
    }
    btnDashboardImportFolder.setOnClickListener {
      val hddFile = resolveHddFile()
      if (hddFile == null) {
        Toast.makeText(this, R.string.settings_hdd_status_missing, Toast.LENGTH_LONG).show()
        return@setOnClickListener
      }
      if (isImportingDashboard) return@setOnClickListener
      pickDashboardFolder.launch(null)
    }
  }

  private fun setDashboardImportEnabled(enabled: Boolean) {
    btnDashboardImportZip.isEnabled = enabled
    btnDashboardImportFolder.isEnabled = enabled
  }

  private fun showDashboardImportSourcePicker() {
    val hddFile = resolveHddFile()
    if (hddFile == null) {
      Toast.makeText(this, R.string.settings_hdd_status_missing, Toast.LENGTH_LONG).show()
      return
    }
    if (isImportingDashboard) {
      return
    }

    val labels = arrayOf(
      getString(R.string.settings_dashboard_import_source_zip),
      getString(R.string.settings_dashboard_import_source_folder),
    )
    val dp = resources.displayMetrics.density
    lateinit var importDialog: androidx.appcompat.app.AlertDialog

    val buttonList = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), 0)
      labels.forEachIndexed { i, label ->
        addView(MaterialButton(this@SettingsActivity, null,
          com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
          text = label
          layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
          ).also { lp -> lp.bottomMargin = (8 * dp).toInt() }
          setOnClickListener {
            importDialog.dismiss()
            when (i) {
              0 -> pickDashboardZip.launch(arrayOf("application/zip", "application/octet-stream"))
              else -> pickDashboardFolder.launch(null)
            }
          }
        })
      }
    }

    importDialog = MaterialAlertDialogBuilder(this)
      .setTitle(R.string.settings_dashboard_import_source_title)
      .setView(buttonList)
      .setNegativeButton(android.R.string.cancel, null)
      .create()
    importDialog.show()
  }

  private fun prepareDashboardImportFromZip(uri: Uri) {
    val hddFile = resolveHddFile()
    if (hddFile == null) {
      Toast.makeText(this, R.string.settings_hdd_status_missing, Toast.LENGTH_LONG).show()
      return
    }

    startDashboardImportPreparation(hddFile) { workingDir ->
      extractDashboardZipToDirectory(uri, workingDir)
    }
  }

  private fun prepareDashboardImportFromFolder(uri: Uri) {
    val hddFile = resolveHddFile()
    if (hddFile == null) {
      Toast.makeText(this, R.string.settings_hdd_status_missing, Toast.LENGTH_LONG).show()
      return
    }

    startDashboardImportPreparation(hddFile) { workingDir ->
      copyDashboardTreeToDirectory(uri, workingDir)
    }
  }

  private fun startDashboardImportPreparation(
    hddFile: File,
    prepareSource: (File) -> File,
  ) {
    if (isImportingDashboard) {
      return
    }

    isImportingDashboard = true
    setDashboardImportEnabled(false)
    Toast.makeText(this, R.string.settings_dashboard_import_preparing, Toast.LENGTH_SHORT).show()

    Thread {
      var workingDir: File? = null
      val result = runCatching {
        workingDir = createDashboardWorkingDirectory()
        val preparedRoot = prepareSource(workingDir!!)
        val sourceRoot = normalizeDashboardSourceRoot(preparedRoot)
        if (!dashboardSourceHasFiles(sourceRoot)) {
          throw IOException(getString(R.string.settings_dashboard_import_empty))
        }
        val importLayoutRoot = buildDashboardImportLayout(sourceRoot, workingDir!!)
        val bootPreparation = prepareDashboardBootFiles(importLayoutRoot)

        DashboardImportPlan(
          hddFile = hddFile,
          workingDir = workingDir!!,
          sourceDir = importLayoutRoot,
          backupDir = createDashboardBackupDirectory(),
          summary = describeDashboardSource(importLayoutRoot),
          bootNote = bootPreparation.note,
          bootAliasCreated = bootPreparation.aliasCreated,
          retailBootReady = bootPreparation.retailBootReady,
        )
      }

      runOnUiThread {
        result.onSuccess { plan ->
          showDashboardImportConfirmation(plan)
        }.onFailure { error ->
          workingDir?.deleteRecursively()
          isImportingDashboard = false
          setDashboardImportEnabled(true)
          Toast.makeText(
            this,
            getString(
              R.string.settings_dashboard_import_failed,
              error.message ?: getString(R.string.settings_dashboard_import_empty),
            ),
            Toast.LENGTH_LONG,
          ).show()
        }
      }
    }.start()
  }

  private fun showDashboardImportConfirmation(plan: DashboardImportPlan) {
    MaterialAlertDialogBuilder(this)
      .setTitle(R.string.settings_dashboard_import_title)
      .setMessage(
        buildString {
          append(
            getString(
              R.string.settings_dashboard_import_message,
              plan.summary,
              plan.backupDir.absolutePath,
            )
          )
          if (!plan.bootNote.isNullOrBlank()) {
            append("\n\n")
            append(plan.bootNote)
          }
        }
      )
      .setPositiveButton(R.string.settings_dashboard_import_action) { _, _ ->
        importDashboard(plan)
      }
      .setNegativeButton(android.R.string.cancel) { _, _ ->
        plan.workingDir.deleteRecursively()
        isImportingDashboard = false
        setDashboardImportEnabled(true)
      }
      .setOnCancelListener {
        plan.workingDir.deleteRecursively()
        isImportingDashboard = false
        setDashboardImportEnabled(true)
      }
      .show()
  }

  private fun importDashboard(plan: DashboardImportPlan) {
    Toast.makeText(this, R.string.settings_dashboard_import_working, Toast.LENGTH_SHORT).show()

    Thread {
      val result = runCatching {
        XboxDashboardImporter.importDashboard(
          hddFile = plan.hddFile,
          sourceRoot = plan.sourceDir,
          backupRoot = plan.backupDir,
        )
      }

      runOnUiThread {
        plan.workingDir.deleteRecursively()
        isImportingDashboard = false
        setDashboardImportEnabled(true)
        refreshInsigniaStatus()
        result.onSuccess {
          val messageRes = when {
            plan.bootAliasCreated -> R.string.settings_dashboard_import_success_with_alias
            !plan.retailBootReady -> R.string.settings_dashboard_import_success_without_retail_boot
            else -> R.string.settings_dashboard_import_success
          }
          Toast.makeText(this, getString(messageRes, plan.backupDir.absolutePath), Toast.LENGTH_LONG).show()
        }.onFailure { error ->
          Toast.makeText(
            this,
            getString(
              R.string.settings_dashboard_import_failed,
              error.message ?: plan.hddFile.absolutePath,
            ),
            Toast.LENGTH_LONG,
          ).show()
        }
      }
    }.start()
  }

  private fun createDashboardWorkingDirectory(): File {
    val dir = File(cacheDir, "dashboard-import-${System.currentTimeMillis()}")
    if (!dir.mkdirs()) {
      throw IOException("Failed to prepare a temporary dashboard import folder.")
    }
    return dir
  }

  private fun createDashboardBackupDirectory(): File {
    val base = getExternalFilesDir(null) ?: filesDir
    val root = File(File(base, "x1box"), "dashboard-backups")
    val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    val dir = File(root, "dashboard-$stamp")
    if (!dir.mkdirs()) {
      throw IOException("Failed to prepare the dashboard backup folder.")
    }
    return dir
  }

  private fun extractDashboardZipToDirectory(uri: Uri, targetDir: File): File {
    val canonicalRoot = targetDir.canonicalFile
    contentResolver.openInputStream(uri)?.use { rawInput ->
      ZipInputStream(BufferedInputStream(rawInput)).use { zip ->
        while (true) {
          val entry = zip.nextEntry ?: break
          if (entry.name.isBlank()) {
            continue
          }
          val outFile = File(targetDir, entry.name).canonicalFile
          val rootPath = canonicalRoot.path + File.separator
          if (outFile.path != canonicalRoot.path && !outFile.path.startsWith(rootPath)) {
            throw IOException("The selected ZIP contains an invalid path.")
          }
          if (entry.isDirectory) {
            if (!outFile.exists() && !outFile.mkdirs()) {
              throw IOException("Failed to create ${outFile.name} from the ZIP.")
            }
            continue
          }

          outFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
              throw IOException("Failed to create ${parent.name} from the ZIP.")
            }
          }
          FileOutputStream(outFile).use { output ->
            zip.copyTo(output)
          }
          zip.closeEntry()
        }
      }
    } ?: throw IOException("Failed to open the selected dashboard ZIP.")

    return targetDir
  }

  private fun copyDashboardTreeToDirectory(uri: Uri, targetDir: File): File {
    val root = DocumentFile.fromTreeUri(this, uri)
      ?: throw IOException("Failed to open the selected dashboard folder.")
    copyDocumentFileRecursively(root, targetDir)
    return targetDir
  }

  private fun copyDocumentFileRecursively(source: DocumentFile, target: File) {
    if (source.isDirectory) {
      val children = source.listFiles()
      for (child in children) {
        val name = child.name ?: continue
        val childTarget = File(target, name)
        if (child.isDirectory) {
          if (!childTarget.exists() && !childTarget.mkdirs()) {
            throw IOException("Failed to create ${childTarget.name}.")
          }
          copyDocumentFileRecursively(child, childTarget)
        } else if (child.isFile) {
          childTarget.parentFile?.mkdirs()
          contentResolver.openInputStream(child.uri)?.use { input ->
            FileOutputStream(childTarget).use { output ->
              input.copyTo(output)
            }
          } ?: throw IOException("Failed to copy ${child.name}.")
        }
      }
      return
    }

    if (source.isFile) {
      contentResolver.openInputStream(source.uri)?.use { input ->
        FileOutputStream(target).use { output ->
          input.copyTo(output)
        }
      } ?: throw IOException("Failed to copy ${source.name}.")
    }
  }

  private fun normalizeDashboardSourceRoot(root: File): File {
    var current = root

    while (true) {
      val children = dashboardSourceEntries(current)
      if (children.size != 1 || !children.first().isDirectory) {
        break
      }
      current = children.first()
    }

    if (looksLikeDashboardSourceRoot(current)) {
      return current
    }

    return findNestedDashboardSourceRoot(current) ?: current
  }

  private fun buildDashboardImportLayout(sourceRoot: File, workingDir: File): File {
    val entries = sourceRoot.listFiles()
      ?.filterNot { shouldSkipDashboardSourceEntry(it.name) }
      .orEmpty()
    val layoutRoot = File(workingDir, "dashboard-layout")
    if (layoutRoot.exists()) {
      layoutRoot.deleteRecursively()
    }
    if (!layoutRoot.mkdirs()) {
      throw IOException("Failed to prepare the dashboard import layout.")
    }

    val sourceC = entries.firstOrNull { it.isDirectory && it.name.equals("C", ignoreCase = true) }
      ?.let(::normalizeDashboardPartitionRoot)
    val sourceE = entries.firstOrNull { it.isDirectory && it.name.equals("E", ignoreCase = true) }
      ?.let(::normalizeDashboardPartitionRoot)
    val rootEntriesForC = if (sourceC == null) entries.filterNot { entry ->
      entry.isDirectory && (entry.name.equals("C", ignoreCase = true) || entry.name.equals("E", ignoreCase = true))
    } else {
      emptyList()
    }

    sourceC?.let { copyLocalDirectoryContents(it, File(layoutRoot, "C")) }
    if (rootEntriesForC.isNotEmpty()) {
      val targetC = File(layoutRoot, "C")
      for (entry in rootEntriesForC) {
        copyLocalEntry(entry, File(targetC, entry.name))
      }
    }

    sourceE?.let { copyLocalDirectoryContents(it, File(layoutRoot, "E")) }

    return layoutRoot
  }

  private fun normalizeDashboardPartitionRoot(partitionDir: File): File {
    if (!partitionDir.isDirectory) {
      return partitionDir
    }

    var current = partitionDir
    while (true) {
      if (looksLikeDashboardSourceRoot(current)) {
        return current
      }

      val children = dashboardSourceEntries(current).filter { it.isDirectory }
      if (children.size != 1) {
        break
      }
      current = children.first()
    }

    return findNestedDashboardSourceRoot(current) ?: current
  }

  private fun copyLocalDirectoryContents(sourceDir: File, targetDir: File) {
    val children = sourceDir.listFiles().orEmpty()
    if (!targetDir.exists() && !targetDir.mkdirs()) {
      throw IOException("Failed to create ${targetDir.name}.")
    }
    for (child in children) {
      if (shouldSkipDashboardSourceEntry(child.name)) {
        continue
      }
      copyLocalEntry(child, File(targetDir, child.name))
    }
  }

  private fun copyLocalEntry(source: File, target: File) {
    if (source.isDirectory) {
      if (!target.exists() && !target.mkdirs()) {
        throw IOException("Failed to create ${target.name}.")
      }
      for (child in source.listFiles().orEmpty()) {
        if (shouldSkipDashboardSourceEntry(child.name)) {
          continue
        }
        copyLocalEntry(child, File(target, child.name))
      }
      return
    }

    target.parentFile?.let { parent ->
      if (!parent.exists() && !parent.mkdirs()) {
        throw IOException("Failed to create ${parent.name}.")
      }
    }
    source.copyTo(target, overwrite = true)
  }

  private fun prepareDashboardBootFiles(layoutRoot: File): DashboardBootPreparation {
    val cDir = File(layoutRoot, "C")
    if (!cDir.isDirectory || !cDir.exists()) {
      return DashboardBootPreparation(
        note = getString(R.string.settings_dashboard_import_boot_missing_note),
        aliasCreated = false,
        retailBootReady = false,
      )
    }

    val topLevelFiles = cDir.listFiles()
      ?.filter { it.isFile }
      .orEmpty()
    val xboxdash = topLevelFiles.firstOrNull { it.name.equals("xboxdash.xbe", ignoreCase = true) }
    if (xboxdash != null) {
      return DashboardBootPreparation(
        note = null,
        aliasCreated = false,
        retailBootReady = true,
      )
    }

    val candidate = findDashboardBootCandidate(cDir)

    if (candidate != null) {
      val aliasFile = File(cDir, "xboxdash.xbe")
      candidate.copyTo(aliasFile, overwrite = true)
      val relativePath = candidate.relativeTo(cDir).invariantSeparatorsPath
      return DashboardBootPreparation(
        note = getString(R.string.settings_dashboard_import_boot_alias_note, relativePath),
        aliasCreated = true,
        retailBootReady = true,
      )
    }

    return DashboardBootPreparation(
      note = getString(R.string.settings_dashboard_import_boot_missing_note),
      aliasCreated = false,
      retailBootReady = false,
    )
  }

  private fun findDashboardBootCandidate(cDir: File): File? {
    var bestFile: File? = null
    var bestScore = Int.MIN_VALUE

    cDir.walkTopDown().forEach { file ->
      if (!file.isFile || !file.extension.equals("xbe", ignoreCase = true)) {
        return@forEach
      }

      val score = scoreDashboardBootCandidate(cDir, file)
      if (score > bestScore) {
        bestScore = score
        bestFile = file
      }
    }

    return bestFile
  }

  private fun scoreDashboardBootCandidate(cDir: File, candidate: File): Int {
    val relativePath = candidate.relativeTo(cDir).invariantSeparatorsPath.lowercase(Locale.US)
    val fileName = candidate.name.lowercase(Locale.US)
    val baseName = candidate.nameWithoutExtension.lowercase(Locale.US)
    val depth = relativePath.count { it == '/' }
    var score = 0

    score += when (fileName) {
      "xboxdash.xbe" -> 12_000
      "default.xbe" -> 10_000
      "evoxdash.xbe" -> 9_500
      "avalaunch.xbe" -> 9_400
      "unleashx.xbe" -> 9_300
      "xbmc.xbe" -> 9_200
      "nexgen.xbe" -> 9_100
      else -> 0
    }

    if (baseName.contains("dash")) {
      score += 800
    }
    if (relativePath.contains("/dashboard/") || relativePath.contains("/dash/")) {
      score += 500
    }
    if (relativePath.startsWith("dashboard/") || relativePath.startsWith("dash/")) {
      score += 400
    }
    if (relativePath.contains("/apps/") || relativePath.contains("/games/")) {
      score -= 1_000
    }
    if (baseName.contains("installer") || baseName.contains("uninstall") || baseName.contains("config")) {
      score -= 2_000
    }

    score += 300 - (depth * 40)
    return score
  }

  private fun looksLikeDashboardSourceRoot(root: File): Boolean {
    val entries = dashboardSourceEntries(root)
    if (entries.isEmpty()) {
      return false
    }

    val hasPartitionDir = entries.any { entry ->
      entry.isDirectory &&
        (entry.name.equals("C", ignoreCase = true) || entry.name.equals("E", ignoreCase = true)) &&
        dashboardSourceEntries(entry).isNotEmpty()
    }
    if (hasPartitionDir) {
      return true
    }

    return scoreDashboardSourceRoot(root, root) > 0
  }

  private fun findNestedDashboardSourceRoot(root: File): File? {
    var bestDir: File? = null
    var bestScore = Int.MIN_VALUE

    root.walkTopDown()
      .maxDepth(8)
      .forEach { candidate ->
        if (!candidate.isDirectory || candidate == root) {
          return@forEach
        }

        val score = scoreDashboardSourceRoot(root, candidate)
        if (score > bestScore) {
          bestScore = score
          bestDir = candidate
        }
      }

    return bestDir?.takeIf { bestScore > 0 }
  }

  private fun scoreDashboardSourceRoot(searchRoot: File, candidate: File): Int {
    val entries = dashboardSourceEntries(candidate)
    if (entries.isEmpty()) {
      return Int.MIN_VALUE
    }

    val partitionDirs = entries.filter { entry ->
      entry.isDirectory &&
        (entry.name.equals("C", ignoreCase = true) || entry.name.equals("E", ignoreCase = true)) &&
        dashboardSourceEntries(entry).isNotEmpty()
    }
    val directFiles = entries.filter { it.isFile }
    val directDirs = entries.filter { it.isDirectory }

    var score = 0
    if (partitionDirs.isNotEmpty()) {
      score += 10_000
    }
    if (directFiles.any { it.name.equals("xboxdash.xbe", ignoreCase = true) }) {
      score += 9_000
    }
    if (directFiles.any { it.name.equals("msdash.xbe", ignoreCase = true) }) {
      score += 7_000
    }
    if (directFiles.any { it.name.equals("xbox.xtf", ignoreCase = true) }) {
      score += 3_000
    }
    if (directDirs.any { it.name.equals("xodash", ignoreCase = true) }) {
      score += 3_000
    }
    if (directDirs.any { it.name.equals("audio", ignoreCase = true) }) {
      score += 1_500
    }
    if (directDirs.any { it.name.equals("fonts", ignoreCase = true) }) {
      score += 1_500
    }
    if (directFiles.any { it.extension.equals("xbe", ignoreCase = true) }) {
      score += 1_000
    }

    if (score <= 0) {
      return score
    }

    val depth = candidate.relativeTo(searchRoot)
      .invariantSeparatorsPath
      .count { it == '/' } + 1
    return score - (depth * 120)
  }

  private fun describeDashboardSource(root: File): String {
    val sourceC = File(root, "C")
    val sourceE = File(root, "E")
    val hasC = sourceC.isDirectory && sourceC.walkTopDown().any { it.isFile }
    val hasE = sourceE.isDirectory && sourceE.walkTopDown().any { it.isFile }

    return when {
      hasC && hasE -> getString(R.string.settings_dashboard_import_summary_c_e)
      hasE -> getString(R.string.settings_dashboard_import_summary_e)
      else -> getString(R.string.settings_dashboard_import_summary_c)
    }
  }

  private fun dashboardSourceHasFiles(root: File): Boolean {
    return looksLikeDashboardSourceRoot(root) || findNestedDashboardSourceRoot(root) != null
  }

  private fun dashboardSourceEntries(root: File): List<File> {
    return root.listFiles()
      ?.filterNot { shouldSkipDashboardSourceEntry(it.name) }
      .orEmpty()
  }

  private fun shouldSkipDashboardSourceEntry(name: String): Boolean {
    return name == ".DS_Store" || name == "__MACOSX"
  }

  // ── Utility ────────────────────────────────────────────────────────────

  private fun persistUriPermission(uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    try {
      contentResolver.takePersistableUriPermission(uri, flags)
    } catch (_: SecurityException) {
    }
  }

  private fun hasPersistedReadPermission(uri: Uri): Boolean {
    return contentResolver.persistedUriPermissions.any { permission ->
      permission.isReadPermission && permission.uri == uri
    }
  }

  private fun getFileName(uri: Uri): String? {
    return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
      if (col >= 0 && cursor.moveToFirst()) cursor.getString(col) else null
    }
  }

  private fun isZipSelection(uri: Uri): Boolean {
    val name = getFileName(uri) ?: uri.lastPathSegment ?: return false
    return isZipSelection(name)
  }

  private fun isZipSelection(name: String): Boolean {
    return name.lowercase(Locale.US).endsWith(".zip")
  }
}
