package com.rfandango.haku_x

import android.content.Intent
import android.content.res.Configuration
import android.text.Editable
import android.text.TextWatcher
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Space
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class GameLibraryActivity : AppCompatActivity() {
  private data class GameEntry(
    val title: String,
    val uri: Uri,
    val relativePath: String,
    val sizeBytes: Long
  )

  private data class CoverEntry(
    val collapsed: String,
    val tokens: Set<String>,
    val numericTokens: Set<String>,
    val url: String
  )

  private val prefs by lazy { getSharedPreferences("x1box_prefs", MODE_PRIVATE) }
  private val gameExts = setOf("iso", "xiso", "cso", "cci")
  private val titleStopWords = setOf("the", "a", "an", "and", "of", "for", "in", "on", "to")
  private val coverRepoBaseUrl = "https://raw.githubusercontent.com/izzy2lost/X1_Covers/main/"
  private val boxArtCache = ConcurrentHashMap<String, String>()
  private val boxArtMisses = ConcurrentHashMap.newKeySet<String>()
  private val coverIndex = ConcurrentHashMap<String, String>()
  private val coverCollapsedIndex = ConcurrentHashMap<String, String>()
  private val coverEntries = ArrayList<CoverEntry>()
  @Volatile private var coverIndexLoaded = false

  private lateinit var loadingSpinner: ProgressBar
  private lateinit var loadingText: TextView
  private lateinit var emptyText: TextView
  private lateinit var gamesListContainer: LinearLayout
  private lateinit var gamesGridContainer: LinearLayout
  private lateinit var btnSettings: ImageButton
  private lateinit var viewModeToggle: MaterialButtonToggleGroup
  private lateinit var switchBoxArtLookup: MaterialSwitch

  private var gamesFolderUri: Uri? = null
  private var scanGeneration = 0
  private var currentGames: List<GameEntry> = emptyList()
  private var searchFilter = ""
  private var useCoverGrid = false
  private var boxArtLookupEnabled = true
  @Volatile private var isConvertingIso = false
  @Volatile private var convertCancelled = false
  private var convertThread: Thread? = null
  private var convertOutputDoc: DocumentFile? = null
  private var convertStageDir: File? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_game_library)

    // Clean up any leftover temp files from a previous interrupted conversion
    cleanupStagingDir()

    loadingSpinner = findViewById(R.id.library_loading)
    loadingText = findViewById(R.id.library_loading_text)
    emptyText = findViewById(R.id.library_empty_text)
    gamesListContainer = findViewById(R.id.library_games_container)
    gamesGridContainer = findViewById(R.id.library_games_grid_container)
    btnSettings = findViewById(R.id.btn_library_settings)
    viewModeToggle = findViewById(R.id.library_view_mode_toggle)
    switchBoxArtLookup = findViewById(R.id.switch_box_art_lookup)

    gamesFolderUri = prefs.getString("gamesFolderUri", null)?.let(Uri::parse)
    useCoverGrid = prefs.getBoolean("library_cover_grid", false)
    boxArtLookupEnabled = prefs.getBoolean("library_box_art_lookup", true)

    switchBoxArtLookup.isChecked = boxArtLookupEnabled
    viewModeToggle.check(if (useCoverGrid) R.id.btn_view_grid else R.id.btn_view_list)
    syncDisplayModeUi()

    btnSettings.setOnClickListener {
      startActivity(Intent(this, SettingsActivity::class.java))
    }
    findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(R.id.library_swipe_refresh).apply {
      setOnRefreshListener {
        loadGames()
        isRefreshing = false
      }
    }
    viewModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
      if (!isChecked) {
        return@addOnButtonCheckedListener
      }
      val nextGrid = checkedId == R.id.btn_view_grid
      if (nextGrid == useCoverGrid) {
        return@addOnButtonCheckedListener
      }
      useCoverGrid = nextGrid
      prefs.edit().putBoolean("library_cover_grid", useCoverGrid).apply()
      syncDisplayModeUi()
      renderGames()
    }
    switchBoxArtLookup.setOnCheckedChangeListener { _, checked ->
      boxArtLookupEnabled = checked
      prefs.edit().putBoolean("library_box_art_lookup", checked).apply()
      if (useCoverGrid) {
        renderGames()
      }
    }
    findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.library_search)
      .addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
          searchFilter = s?.toString()?.trim() ?: ""
          renderGames()
        }
      })
    if (!isFolderReady(gamesFolderUri)) {
      Toast.makeText(this, getString(R.string.library_no_folder), Toast.LENGTH_SHORT).show()
      return
    }

    loadGames()
  }

  private fun loadGames() {
    val folderUri = gamesFolderUri
    if (!isFolderReady(folderUri)) {
      setLoading(false)
      currentGames = emptyList()
      renderGames()
      return
    }
    setLoading(true, getString(R.string.library_loading_games))

    val generation = ++scanGeneration
    Thread {
      val games = scanFolderForGames(folderUri!!)
      runOnUiThread {
        if (generation != scanGeneration) {
          return@runOnUiThread
        }
        setLoading(false)
        currentGames = games
        renderGames()
      }
    }.start()
  }

  private fun syncDisplayModeUi() {
    switchBoxArtLookup.visibility = if (useCoverGrid) View.VISIBLE else View.GONE
    gamesListContainer.visibility = if (useCoverGrid) View.GONE else View.VISIBLE
    gamesGridContainer.visibility = if (useCoverGrid) View.VISIBLE else View.GONE
  }

  private fun setLoading(loading: Boolean, message: String? = null) {
    if (message != null) {
      loadingText.text = message
    }
    loadingSpinner.visibility = if (loading) View.VISIBLE else View.GONE
    loadingText.visibility = if (loading) View.VISIBLE else View.GONE
  }

  private fun renderGames() {
    val games = if (searchFilter.isEmpty()) currentGames
      else currentGames.filter { it.title.contains(searchFilter, ignoreCase = true) }
    syncDisplayModeUi()
    gamesListContainer.removeAllViews()
    gamesGridContainer.removeAllViews()
    emptyText.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
    if (games.isEmpty()) {
      return
    }

    if (useCoverGrid) {
      renderCoverGrid(games)
    } else {
      renderList(games)
    }
  }


  private fun renderList(games: List<GameEntry>) {
    val inflater = LayoutInflater.from(this)
    for (game in games) {
      val item = inflater.inflate(R.layout.item_game_entry, gamesListContainer, false)
      val nameText = item.findViewById<TextView>(R.id.game_name_text)
      val sizeText = item.findViewById<TextView>(R.id.game_size_text)
      val pathText = item.findViewById<TextView>(R.id.game_path_text)

      nameText.text = game.title
      sizeText.text = getString(R.string.library_game_size, formatSize(game.sizeBytes))
      pathText.text = getString(R.string.library_game_path, game.relativePath)

      item.setOnClickListener { launchGame(game) }
      gamesListContainer.addView(item)
    }
  }

  private fun renderCoverGrid(games: List<GameEntry>) {
    val inflater = LayoutInflater.from(this)
    var row: LinearLayout? = null
    val columns = resolveCoverGridColumns()
    val spacingPx = dp(8)
    val halfSpacingPx = spacingPx / 2

    for ((index, game) in games.withIndex()) {
      val columnIndex = index % columns
      if (columnIndex == 0) {
        row = LinearLayout(this).apply {
          orientation = LinearLayout.HORIZONTAL
        }
        val rowLp = LinearLayout.LayoutParams(
          LinearLayout.LayoutParams.MATCH_PARENT,
          LinearLayout.LayoutParams.WRAP_CONTENT
        )
        if (index >= columns) {
          rowLp.topMargin = dp(12)
        }
        gamesGridContainer.addView(row, rowLp)
      }

      val item = inflater.inflate(R.layout.item_game_cover, row, false)
      val itemLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
      itemLp.marginStart = if (columnIndex == 0) 0 else halfSpacingPx
      itemLp.marginEnd = if (columnIndex == columns - 1) 0 else halfSpacingPx
      row!!.addView(item, itemLp)

      val nameText = item.findViewById<TextView>(R.id.game_cover_name_text)
      val sizeText = item.findViewById<TextView>(R.id.game_cover_size_text)
      val coverImage = item.findViewById<ImageView>(R.id.game_cover_image)

      nameText.text = game.title
      sizeText.text = getString(R.string.library_game_size, formatSize(game.sizeBytes))
      item.setOnClickListener { launchGame(game) }
      bindCoverArt(coverImage, game)
    }

    val remainder = games.size % columns
    if (remainder != 0) {
      for (columnIndex in remainder until columns) {
        val filler = Space(this)
        val fillerLp = LinearLayout.LayoutParams(0, 0, 1f)
        fillerLp.marginStart = if (columnIndex == 0) 0 else halfSpacingPx
        fillerLp.marginEnd = if (columnIndex == columns - 1) 0 else halfSpacingPx
        row?.addView(filler, fillerLp)
      }
    }
  }

  private fun resolveCoverGridColumns(): Int {
    val widthDp = resources.configuration.screenWidthDp
    val suggested = (widthDp / 180).coerceIn(2, 4)
    return if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
      maxOf(3, suggested)
    } else {
      suggested
    }
  }

  private fun bindCoverArt(coverView: ImageView, game: GameEntry) {
    coverView.tag = game.uri.toString()
    coverView.setImageResource(android.R.drawable.ic_menu_report_image)

    if (!boxArtLookupEnabled) {
      return
    }

    val key = normalizeCoverKey(game.title)
    val cachedUrl = boxArtCache[key]
    if (cachedUrl != null) {
      applyBoxArtToView(coverView, cachedUrl)
      return
    }

    val url = lookupBoxArtUrl(game.title) ?: return
    boxArtCache[key] = url
    if (coverView.tag == game.uri.toString()) {
      applyBoxArtToView(coverView, url)
    }
  }

  private fun applyBoxArtToView(coverView: ImageView, url: String) {
    coverView.load(url) {
      crossfade(true)
      placeholder(android.R.drawable.ic_menu_report_image)
      error(android.R.drawable.ic_menu_report_image)
    }
  }

  private fun lookupBoxArtUrl(title: String): String? {
    ensureCoverIndexLoaded()
    val candidates = linkedSetOf<String>()
    val cleanTitle = normalizeLookupTitle(title)
    val normalizedTitle = normalizeCoverKey(cleanTitle)
    if (normalizedTitle.isBlank()) {
      return null
    }
    if (boxArtMisses.contains(normalizedTitle)) {
      return null
    }
    addCoverLookupCandidates(candidates, title)
    addCoverLookupCandidates(candidates, cleanTitle)
    addCoverLookupCandidates(candidates, cleanTitle.replace(":", ""))
    addCoverLookupCandidates(candidates, cleanTitle.substringBefore(" - ").trim())
    addCoverLookupCandidates(candidates, cleanTitle.substringBefore(":").trim())

    for (key in candidates) {
      val found = coverIndex[key]
      if (!found.isNullOrBlank()) {
        return found
      }
    }

    for (key in candidates) {
      val collapsed = collapseCoverKey(key)
      if (collapsed.isBlank()) {
        continue
      }
      val found = coverCollapsedIndex[collapsed]
      if (!found.isNullOrBlank()) {
        coverIndex.putIfAbsent(key, found)
        return found
      }
    }

    val fuzzyMatch = findClosestCoverUrl(candidates)
    if (!fuzzyMatch.isNullOrBlank()) {
      for (key in candidates) {
        coverIndex.putIfAbsent(key, fuzzyMatch)
        val collapsed = collapseCoverKey(key)
        if (collapsed.isNotBlank()) {
          coverCollapsedIndex.putIfAbsent(collapsed, fuzzyMatch)
        }
      }
      return fuzzyMatch
    }

    boxArtMisses.add(normalizedTitle)
    return null
  }

  private fun addCoverLookupCandidates(out: MutableSet<String>, raw: String) {
    if (raw.isBlank()) {
      return
    }
    val normalized = normalizeCoverKey(raw)
    if (normalized.isBlank()) {
      return
    }
    out.add(normalized)
    out.add(stripTrailingRegion(normalized))
  }

  private fun ensureCoverIndexLoaded() {
    if (coverIndexLoaded) {
      return
    }
    try {
      val lines = assets.open("X1_Covers.txt").bufferedReader().use { it.readLines() }
      val seenEntries = HashSet<String>()
      for (line in lines) {
        val fileName = line.trim()
        if (fileName.isEmpty() || !fileName.endsWith(".png", ignoreCase = true)) {
          continue
        }
        val gameName = fileName.removeSuffix(".png").trim()
        val encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        val url = coverRepoBaseUrl + encoded

        val exactKey = normalizeCoverKey(gameName)
        val strippedKey = stripTrailingRegion(exactKey)
        if (exactKey.isNotEmpty()) {
          coverIndex.putIfAbsent(exactKey, url)
        }
        if (strippedKey.isNotEmpty()) {
          coverIndex.putIfAbsent(strippedKey, url)
        }
        val canonical = if (strippedKey.isNotEmpty()) strippedKey else exactKey
        val collapsed = collapseCoverKey(canonical)
        if (collapsed.isNotEmpty()) {
          coverCollapsedIndex.putIfAbsent(collapsed, url)
        }
        if (canonical.isNotEmpty() && seenEntries.add("$canonical|$url")) {
          val tokens = tokenizeCoverKey(canonical)
          coverEntries.add(
            CoverEntry(
              collapsed = collapsed,
              tokens = tokens,
              numericTokens = tokens.filterTo(HashSet()) { token -> token.any(Char::isDigit) },
              url = url
            )
          )
        }
      }
    } catch (_: Exception) {
      // Keep empty index; grid will show placeholders if the asset is unavailable.
    }
    coverIndexLoaded = true
  }

  private fun normalizeLookupTitle(input: String): String {
    var title = input.trim()
    title = title.replace('_', ' ')
    title = title.replace(Regex("\\[[^\\]]*\\]"), " ")
    title = title.replace(Regex("\\([^\\)]*\\)"), " ")
    title = title.replace(Regex("\\s+"), " ").trim()
    return title
  }

  private fun normalizeCoverKey(input: String): String {
    var title = input.lowercase(Locale.ROOT).trim()
    title = title.replace('_', ' ')
    title = title.replace('\u2019', '\'')
    title = title.replace("’", "'")
    title = title.replace(Regex("\\s+"), " ")
    return title
  }

  private fun stripTrailingRegion(input: String): String {
    return input.replace(Regex("\\s*\\([^\\)]*\\)\\s*$"), "").trim()
  }

  private fun collapseCoverKey(input: String): String {
    return normalizeCoverKey(input).replace(Regex("[^a-z0-9]+"), "")
  }

  private fun tokenizeCoverKey(input: String): Set<String> {
    return normalizeCoverKey(input)
      .replace(Regex("[^a-z0-9]+"), " ")
      .split(' ')
      .asSequence()
      .map { it.trim() }
      .filter { it.length >= 2 }
      .filter { it !in titleStopWords }
      .toSet()
  }

  private fun findClosestCoverUrl(candidates: Set<String>): String? {
    var bestUrl: String? = null
    var bestScore = 0
    for (candidate in candidates) {
      val collapsed = collapseCoverKey(candidate)
      val tokens = tokenizeCoverKey(candidate)
      if (collapsed.isBlank() || tokens.isEmpty()) {
        continue
      }
      val numericTokens = tokens.filterTo(HashSet()) { token -> token.any(Char::isDigit) }
      for (entry in coverEntries) {
        val score = scoreCoverMatch(collapsed, tokens, numericTokens, entry)
        if (score > bestScore) {
          bestScore = score
          bestUrl = entry.url
        }
      }
    }
    return if (bestScore >= 55) bestUrl else null
  }

  private fun scoreCoverMatch(
    candidateCollapsed: String,
    candidateTokens: Set<String>,
    candidateNumericTokens: Set<String>,
    entry: CoverEntry
  ): Int {
    if (candidateCollapsed == entry.collapsed) {
      return 100
    }

    if (candidateNumericTokens.isNotEmpty() &&
      entry.numericTokens.isNotEmpty() &&
      candidateNumericTokens != entry.numericTokens
    ) {
      return 0
    }

    val overlapCount = candidateTokens.count { token -> entry.tokens.contains(token) }
    if (overlapCount == 0) {
      return 0
    }

    val maxTokenCount = maxOf(candidateTokens.size, entry.tokens.size)
    var score = (overlapCount * 70) / maxTokenCount

    if (candidateCollapsed.contains(entry.collapsed) || entry.collapsed.contains(candidateCollapsed)) {
      score += 20
    }

    val lengthDelta = kotlin.math.abs(candidateCollapsed.length - entry.collapsed.length)
    if (lengthDelta <= 4) {
      score += 10
    } else if (lengthDelta <= 10) {
      score += 5
    }

    return score
  }



  private fun convertIsoToXisoInFolder(
    game: GameEntry,
    outputName: String,
    overwrite: Boolean,
    onProgress: ((phase: Int, percent: Int) -> Unit)? = null
  ): String? {
    val folderUri = gamesFolderUri ?: return getString(R.string.library_no_folder)
    val root = DocumentFile.fromTreeUri(this, folderUri)
      ?: return getString(R.string.library_convert_resolve_failed)
    val parent = resolveParentDirectory(root, game.relativePath)
      ?: return getString(R.string.library_convert_resolve_failed)

    val existing = parent.findFile(outputName)
    if (existing != null && !overwrite) {
      return getString(R.string.library_convert_overwrite_message, outputName)
    }

    val stageDir = File(getExternalFilesDir(null) ?: filesDir, "xiso-convert")
    convertStageDir = stageDir
    if (!stageDir.exists() && !stageDir.mkdirs()) {
      return getString(R.string.library_convert_create_output_failed)
    }

    val token = System.currentTimeMillis()
    val inputTemp = File(stageDir, "input-$token.iso")
    val outputTemp = File(stageDir, "output-$token.xiso.iso")
    try {
      // Phase 0: copy ISO to temp staging
      if (convertCancelled) return "Cancelled"
      if (!copyWithProgress(game.uri, inputTemp, game.sizeBytes) { pct ->
        if (convertCancelled) throw InterruptedException("Conversion cancelled")
        onProgress?.invoke(0, pct)
      }) {
        return getString(R.string.library_convert_copy_input_failed)
      }

      // Phase 1: native XISO conversion (temp → temp)
      if (convertCancelled) return "Cancelled"
      onProgress?.invoke(1, 0)
      val nativeError =
        XisoConverterNative.convertIsoToXiso(inputTemp.absolutePath, outputTemp.absolutePath)
      onProgress?.invoke(1, 100)
      if (convertCancelled) return "Cancelled"
      if (!nativeError.isNullOrBlank()) {
        return nativeError
      }

      if (!outputTemp.exists() || outputTemp.length() <= 0L) {
        return "Converted image was empty"
      }

      // Phase 2: copy finished XISO from staging to ROM folder
      // Only now do we touch the ROM folder — nothing incomplete can be left there.
      if (existing != null && !existing.delete()) {
        return getString(R.string.library_convert_create_output_failed)
      }
      val outputDoc = parent.createFile("application/octet-stream", outputName)
        ?: return getString(R.string.library_convert_create_output_failed)
      convertOutputDoc = outputDoc

      if (!copyFileWithProgress(outputTemp, outputDoc.uri, outputTemp.length()) { pct ->
        if (convertCancelled) throw InterruptedException("Conversion cancelled")
        onProgress?.invoke(2, pct)
      }) {
        outputDoc.delete()
        convertOutputDoc = null
        return getString(R.string.library_convert_copy_output_failed)
      }
      convertOutputDoc = null
      return null
    } catch (_: InterruptedException) {
      return "Cancelled"
    } finally {
      inputTemp.delete()
      outputTemp.delete()
    }
  }

  private fun resolveDocumentFile(root: DocumentFile?, relativePath: String): DocumentFile? {
    if (root == null) return null
    val parts = relativePath.split('/').filter { it.isNotEmpty() }
    var current: DocumentFile = root
    for (part in parts) {
      current = current.findFile(part) ?: return null
    }
    return current
  }

  private fun resolveParentDirectory(root: DocumentFile, relativePath: String): DocumentFile? {
    var dir = root
    val parts = relativePath
      .split('/')
      .map { part -> part.trim() }
      .filter { part -> part.isNotEmpty() }
    if (parts.size <= 1) {
      return dir
    }
    for (index in 0 until (parts.size - 1)) {
      val child = dir.findFile(parts[index]) ?: return null
      if (!child.isDirectory) {
        return null
      }
      dir = child
    }
    return dir
  }

  private fun copyUriToFile(uri: Uri, target: File): Boolean {
    return try {
      val input = contentResolver.openInputStream(uri) ?: return false
      input.use { stream ->
        FileOutputStream(target).use { output ->
          stream.copyTo(output)
        }
      }
      true
    } catch (_: IOException) {
      false
    }
  }

  private fun copyFileToUri(source: File, targetUri: Uri): Boolean {
    return try {
      val output = contentResolver.openOutputStream(targetUri, "w") ?: return false
      FileInputStream(source).use { input ->
        output.use { stream ->
          input.copyTo(stream)
        }
      }
      true
    } catch (_: IOException) {
      false
    }
  }

  private fun copyWithProgress(uri: Uri, target: File, totalBytes: Long, onPercent: (Int) -> Unit): Boolean {
    return try {
      val input = contentResolver.openInputStream(uri) ?: return false
      input.use { stream ->
        FileOutputStream(target).use { output ->
          val buf = ByteArray(65536)
          var copied = 0L
          var lastPct = -1
          while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            copied += n
            if (totalBytes > 0) {
              val pct = (copied * 100 / totalBytes).toInt().coerceIn(0, 100)
              if (pct != lastPct) { lastPct = pct; onPercent(pct) }
            }
          }
        }
      }
      true
    } catch (_: IOException) { false }
  }

  private fun copyFileWithProgress(source: File, targetUri: Uri, totalBytes: Long, onPercent: (Int) -> Unit): Boolean {
    return try {
      val output = contentResolver.openOutputStream(targetUri, "w") ?: return false
      FileInputStream(source).use { input ->
        output.use { stream ->
          val buf = ByteArray(65536)
          var copied = 0L
          var lastPct = -1
          while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            stream.write(buf, 0, n)
            copied += n
            if (totalBytes > 0) {
              val pct = (copied * 100 / totalBytes).toInt().coerceIn(0, 100)
              if (pct != lastPct) { lastPct = pct; onPercent(pct) }
            }
          }
        }
      }
      true
    } catch (_: IOException) { false }
  }

  /**
   * Verify XISO integrity by checking the XDVDFS magic at both the primary
   * (sector 32 = 0x10000) and secondary (last 2KB) volume descriptor locations.
   */
  private fun isXisoIntact(uri: Uri, fileSize: Long): Boolean {
    val magic = "MICROSOFT*XBOX*MEDIA".toByteArray(Charsets.US_ASCII)
    return try {
      contentResolver.openInputStream(uri)?.use { stream ->
        // Check primary descriptor at offset 0x10000
        val skipped = stream.skip(0x10000L)
        if (skipped < 0x10000L) return false
        val buf = ByteArray(magic.size)
        val read = stream.read(buf)
        if (read != magic.size || !buf.contentEquals(magic)) return false
        true
      } ?: false
    } catch (_: Exception) { false }
  }

  private fun isConvertibleIso(game: GameEntry): Boolean {
    val lower = game.relativePath.lowercase(Locale.ROOT)
    return lower.endsWith(".iso") && !lower.endsWith(".xiso.iso")
  }

  private fun buildXisoFileName(sourceName: String): String {
    val lower = sourceName.lowercase(Locale.ROOT)
    val stem = if (lower.endsWith(".iso")) sourceName.dropLast(4) else sourceName
    return "$stem.xiso.iso"
  }

  private fun launchGame(game: GameEntry) {
    // If this is a .xiso.iso file, verify integrity before launching
    val lower = game.relativePath.lowercase(Locale.ROOT)
    if (lower.endsWith(".xiso.iso")) {
      if (!isXisoIntact(game.uri, game.sizeBytes)) {
        // Corrupt XISO — check if original ISO exists to rebuild from
        val isoName = game.relativePath.substringAfterLast('/').let {
          it.removeSuffix(".xiso.iso") + ".iso"
        }
        val folderUri = gamesFolderUri
        val root = if (folderUri != null) DocumentFile.fromTreeUri(this, folderUri) else null
        val parent = if (root != null) resolveParentDirectory(root, game.relativePath) else null
        val origIso = parent?.findFile(isoName)

        if (origIso != null && origIso.isFile && origIso.length() > 0 && XisoConverterNative.isAvailable()) {
          // Rebuild from original ISO
          Toast.makeText(this, getString(R.string.library_xiso_corrupt_rebuilding), Toast.LENGTH_SHORT).show()
          val origGame = GameEntry(game.title, origIso.uri, game.relativePath.substringBeforeLast('/').let {
            if (it.isEmpty()) isoName else "$it/$isoName"
          }, origIso.length())
          launchGameWithAutoConvert(origGame)
        } else {
          Toast.makeText(this, getString(R.string.library_xiso_corrupt), Toast.LENGTH_LONG).show()
          launchGameDirectly(game.uri)
        }
        return
      }
      launchGameDirectly(game.uri)
      return
    }

    if (isConvertibleIso(game) && XisoConverterNative.isAvailable()) {
      val xisoGame = findExistingXiso(game)
      if (xisoGame != null) {
        // Verify existing XISO integrity
        if (isXisoIntact(xisoGame.uri, xisoGame.sizeBytes)) {
          launchGameDirectly(xisoGame.uri)
          return
        }
        // Corrupt — rebuild
        Toast.makeText(this, getString(R.string.library_xiso_corrupt_rebuilding), Toast.LENGTH_SHORT).show()
      }

      if (isXisoContent(game.uri)) {
        launchGameDirectly(game.uri)
        return
      }

      launchGameWithAutoConvert(game)
    } else {
      launchGameDirectly(game.uri)
    }
  }

  private fun launchGameDirectly(uri: Uri) {
    persistUriPermission(uri)
    prefs.edit()
      .putString("dvdUri", uri.toString())
      .remove("dvdPath")
      .putBoolean("skip_game_picker", false)
      .apply()

    startActivity(Intent(this, MainActivity::class.java))
    finish()
  }

  private fun launchGameWithAutoConvert(game: GameEntry) {
    val pad = (24 * resources.displayMetrics.density).toInt()
    val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
      max = 100
      progress = 0
      setPadding(pad, pad / 2, pad, 0)
    }
    val statusText = TextView(this).apply {
      text = getString(R.string.library_autoconvert_preparing)
      setPadding(pad, pad / 4, pad, pad / 2)
      setTextColor(resources.getColor(R.color.xemu_text_muted, theme))
      textSize = 13f
    }
    val layout = android.widget.LinearLayout(this).apply {
      orientation = android.widget.LinearLayout.VERTICAL
      addView(progressBar)
      addView(statusText)
    }
    val dialog = MaterialAlertDialogBuilder(this)
      .setTitle(getString(R.string.library_autoconvert_title_progress, game.title))
      .setCancelable(false)
      .setView(layout)
      .show()

    fun updateProgress(percent: Int, status: String) {
      runOnUiThread {
        progressBar.progress = percent
        statusText.text = status
      }
    }

    convertCancelled = false
    val thread = Thread {
      val outputName = buildXisoFileName(
        game.relativePath.substringAfterLast('/')
      )
      val result = convertIsoToXisoInFolder(game, outputName, overwrite = false) { phase, pct ->
        val base = when (phase) {
          0 -> 0    // copying input: 0-33%
          1 -> 33   // converting: 33-66%
          2 -> 66   // copying output: 66-100%
          else -> 0
        }
        val range = 33
        val total = base + (pct * range / 100).coerceIn(0, range)
        val label = when (phase) {
          0 -> getString(R.string.library_autoconvert_phase_copy_in)
          1 -> getString(R.string.library_autoconvert_phase_convert)
          2 -> getString(R.string.library_autoconvert_phase_copy_out)
          else -> ""
        }
        updateProgress(total, "$label ($pct%)")
      }
      val xisoGame = if (result == null) findExistingXiso(game) else null

      if (xisoGame != null) {
        updateProgress(100, getString(R.string.library_autoconvert_cleanup))
        try {
          val folderUri = gamesFolderUri
          if (folderUri != null) {
            val root = DocumentFile.fromTreeUri(this, folderUri)
            val origDoc = resolveDocumentFile(root, game.relativePath)
            origDoc?.delete()
          }
        } catch (_: Exception) { }
      }

      convertThread = null
      convertOutputDoc = null

      if (convertCancelled) {
        // Activity is being destroyed — don't touch UI
        return@Thread
      }

      runOnUiThread {
        dialog.dismiss()
        if (xisoGame != null) {
          launchGameDirectly(xisoGame.uri)
        } else {
          Toast.makeText(
            this,
            getString(R.string.library_autoconvert_failed, result ?: "unknown"),
            Toast.LENGTH_LONG
          ).show()
          launchGameDirectly(game.uri)
        }
      }
    }
    convertThread = thread
    thread.start()
  }

  /**
   * Check if the file content is already XISO (XDVDFS) format by reading the
   * volume descriptor magic at sector 32 (offset 0x10000).
   */
  private fun isXisoContent(uri: Uri): Boolean {
    val magic = "MICROSOFT*XBOX*MEDIA".toByteArray(Charsets.US_ASCII)
    return try {
      contentResolver.openInputStream(uri)?.use { stream ->
        val skipped = stream.skip(0x10000L)
        if (skipped < 0x10000L) return false
        val buf = ByteArray(magic.size)
        val read = stream.read(buf)
        read == magic.size && buf.contentEquals(magic)
      } ?: false
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Look for an existing .xiso.iso file next to the original game file.
   */
  private fun findExistingXiso(game: GameEntry): GameEntry? {
    val folderUri = gamesFolderUri ?: return null
    val root = DocumentFile.fromTreeUri(this, folderUri) ?: return null
    val parent = resolveParentDirectory(root, game.relativePath) ?: return null
    val xisoName = buildXisoFileName(game.relativePath.substringAfterLast('/'))
    val xisoDoc = parent.findFile(xisoName)
    if (xisoDoc != null && xisoDoc.isFile && xisoDoc.length() > 0) {
      return GameEntry(
        title = game.title,
        uri = xisoDoc.uri,
        relativePath = game.relativePath.substringBeforeLast('/').let {
          if (it.isEmpty()) xisoName else "$it/$xisoName"
        },
        sizeBytes = xisoDoc.length()
      )
    }
    return null
  }

  private fun scanFolderForGames(folderUri: Uri): List<GameEntry> {
    val root = DocumentFile.fromTreeUri(this, folderUri) ?: return emptyList()
    val stack = ArrayDeque<Pair<DocumentFile, String>>()
    stack.add(root to "")

    val games = ArrayList<GameEntry>()
    while (stack.isNotEmpty()) {
      val (node, prefix) = stack.removeLast()
      val files = try {
        node.listFiles()
      } catch (_: Exception) {
        emptyArray()
      }
      for (child in files) {
        val name = child.name ?: continue
        if (child.isDirectory) {
          stack.add(child to (prefix + name + "/"))
          continue
        }
        if (!child.isFile || !isSupportedGame(name)) {
          continue
        }
        games.add(
          GameEntry(
            title = toGameTitle(name),
            uri = child.uri,
            relativePath = prefix + name,
            sizeBytes = child.length()
          )
        )
      }
    }

    games.sortBy { it.title.lowercase(Locale.ROOT) }
    return games
  }

  private fun isSupportedGame(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    if (lower.endsWith(".xiso.iso")) {
      return true
    }
    val ext = lower.substringAfterLast('.', "")
    return ext.isNotEmpty() && gameExts.contains(ext)
  }

  private fun toGameTitle(fileName: String): String {
    val lower = fileName.lowercase(Locale.ROOT)
    return when {
      lower.endsWith(".xiso.iso") -> fileName.dropLast(".xiso.iso".length)
      fileName.contains('.') -> fileName.substringBeforeLast('.')
      else -> fileName
    }
  }

  private fun formatSize(bytes: Long): String {
    if (bytes <= 0L) {
      return "Unknown"
    }
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
      value /= 1024.0
      unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
  }

  private fun isFolderReady(uri: Uri?): Boolean {
    if (uri == null || !hasPersistedReadPermission(uri)) {
      return false
    }
    val root = DocumentFile.fromTreeUri(this, uri) ?: return false
    return root.exists() && root.isDirectory
  }

  private fun persistUriPermission(uri: Uri) {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    try {
      contentResolver.takePersistableUriPermission(uri, flags)
    } catch (_: SecurityException) {
    }
  }

  private fun hasPersistedReadPermission(uri: Uri): Boolean {
    return contentResolver.persistedUriPermissions.any { perm ->
      perm.uri == uri && perm.isReadPermission
    }
  }

  private fun hasPersistedWritePermission(uri: Uri): Boolean {
    return contentResolver.persistedUriPermissions.any { perm ->
      perm.uri == uri && perm.isWritePermission
    }
  }

  private fun formatTreeLabel(uri: Uri): String {
    val name = DocumentFile.fromTreeUri(this, uri)?.name
    if (!name.isNullOrBlank()) {
      return name
    }
    return uri.toString()
  }

  private fun dp(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
  }

  override fun onDestroy() {
    cancelConversion()
    super.onDestroy()
  }

  private fun cancelConversion() {
    if (!isConvertingIso) return
    convertCancelled = true

    // Interrupt the thread to break out of copy loops
    convertThread?.interrupt()
    convertThread = null

    // Delete incomplete output file
    try {
      convertOutputDoc?.delete()
    } catch (_: Exception) { }
    convertOutputDoc = null

    // Clean up staging directory
    cleanupStagingDir()
    isConvertingIso = false
  }

  private fun cleanupStagingDir() {
    val stageDir = convertStageDir
        ?: File(getExternalFilesDir(null) ?: filesDir, "xiso-convert")
    if (stageDir.isDirectory) {
      stageDir.listFiles()?.forEach { it.delete() }
    }
  }

}

