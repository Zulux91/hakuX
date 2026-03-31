package com.rfandango.haku_x

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.view.KeyEvent
import org.libsdl.app.SDLActivity

class MainActivity : SDLActivity(), InputManager.InputDeviceListener {
  private var onScreenController: OnScreenController? = null
  private var controllerBridge: ControllerInputBridge? = null
  private var isControllerVisible = false
  private var inputManager: InputManager? = null
  private var hasPhysicalController = false
  private var pauseMenuOverlay: PauseMenuOverlay? = null
  private var suspendedByLifecycle = false

  private val prefs by lazy { getSharedPreferences("x1box_prefs", MODE_PRIVATE) }
  private var fpsTextView: TextView? = null
  private val fpsHandler = Handler(Looper.getMainLooper())
  private val fpsUpdateInterval = 1000L
  private val fpsRunnable = object : Runnable {
    override fun run() {
      fpsTextView?.text = "FPS: ${nativeGetFps()}"
      fpsHandler.postDelayed(this, fpsUpdateInterval)
    }
  }

  private external fun nativeGetFps(): Int
  private external fun nativePauseEmulation(): Unit
  private external fun nativeResumeEmulation(): Unit
  private external fun nativeExitEmulation(): Unit
  private external fun nativeDumpDiagFrames(numFrames: Int): Unit

  override fun loadLibraries() {
    super.loadLibraries()
    initializeGpuDriver()
  }

  private fun initializeGpuDriver() {
    GpuDriverHelper.init(this)
    if (!GpuDriverHelper.supportsCustomDriverLoading()) {
      android.util.Log.i("MainActivity", "GPU driver: custom loading not supported on this device")
      return
    }

    // Check per-game override: "system" forces system driver, "custom" or null uses installed
    val driverOverride = getSharedPreferences("x1box_prefs", MODE_PRIVATE)
      .getString(PerGameSettingsManager.runtimeKey("gpu_driver"), null)

    if (driverOverride == "system") {
      android.util.Log.i("MainActivity", "GPU driver: per-game override forces system driver")
      return
    }

    val driverLib = GpuDriverHelper.getInstalledDriverLibrary()
    if (driverLib != null) {
      android.util.Log.i("MainActivity", "GPU driver: loading custom driver=$driverLib")
      GpuDriverHelper.initializeDriver(driverLib)
    } else {
      android.util.Log.i("MainActivity", "GPU driver: no custom driver installed, using system default")
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val prefs = getSharedPreferences("x1box_prefs", MODE_PRIVATE)
    // Per-game overrides take precedence over global settings
    val rendererPref = prefs.getString(PerGameSettingsManager.runtimeKey("renderer"), null)
      ?: prefs.getString("renderer", "vulkan") ?: "vulkan"
    SDLActivity.nativeSetenv("XEMU_RENDERER", rendererPref)
    SDLActivity.nativeSetenv("SDL_ANDROID_TRAP_BACK_BUTTON", "1")
    setupOnScreenController()
    setupFpsOverlay()
    setupPauseMenu()
    setupEdgeSwipe()
    setupControllerDetection()
    hideSystemUI()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      hideSystemUI()
    }
  }

  private fun hideSystemUI() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      // Android 11 (API 30) and above
      window.setDecorFitsSystemWindows(false)
      window.insetsController?.let { controller ->
        controller.hide(WindowInsets.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      }
    } else {
      // Android 10 and below
      @Suppress("DEPRECATION")
      window.decorView.systemUiVisibility = (
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        or View.SYSTEM_UI_FLAG_FULLSCREEN
        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
      )
    }
  }

  private fun setupFpsOverlay() {
    fpsTextView = TextView(this).apply {
      text = "FPS: --"
      setTextColor(Color.WHITE)
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
      typeface = Typeface.MONOSPACE
      setShadowLayer(2f, 1f, 1f, Color.BLACK)
      setPadding(16, 8, 16, 8)
      setBackgroundColor(Color.argb(100, 0, 0, 0))
      maxLines = 1
    }
    val params = RelativeLayout.LayoutParams(
      RelativeLayout.LayoutParams.WRAP_CONTENT,
      RelativeLayout.LayoutParams.WRAP_CONTENT
    ).apply {
      addRule(RelativeLayout.ALIGN_PARENT_TOP)
      addRule(RelativeLayout.ALIGN_PARENT_START)
    }
    mLayout?.addView(fpsTextView, params)
  }

  private fun setupPauseMenu() {
    pauseMenuOverlay = PauseMenuOverlay(this).apply {
      onExitEmulation = {
        nativeExitEmulation()
        val intent = Intent(this@MainActivity, GameLibraryActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
        // Kill the process so native QEMU global state is reset.
        // The GameLibraryActivity runs in a fresh process via CLEAR_TASK.
        // Without this, relaunching a game would fail because QEMU's
        // one-shot initialization has already run in this process.
        android.os.Process.killProcess(android.os.Process.myPid())
      }
      onDismiss = {
        togglePauseMenu()
      }
      onDiagCapture = { numFrames ->
        nativeDumpDiagFrames(numFrames)
      }
      isDebugToolsEnabled = {
        prefs.getBoolean("debug_tools", false)
      }
    }
    val overlayParams = RelativeLayout.LayoutParams(
      RelativeLayout.LayoutParams.MATCH_PARENT,
      RelativeLayout.LayoutParams.MATCH_PARENT
    )
    mLayout?.addView(pauseMenuOverlay, overlayParams)
  }

  private fun setupEdgeSwipe() {
    val edgeWidth = TypedValue.applyDimension(
      TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics
    ).toInt()

    val edgeView = View(this)
    val edgeParams = RelativeLayout.LayoutParams(edgeWidth, RelativeLayout.LayoutParams.MATCH_PARENT).apply {
      addRule(RelativeLayout.ALIGN_PARENT_START)
    }

    val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
      override fun onDown(e: MotionEvent): Boolean = true

      override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        if (e1 != null && velocityX > 500f && (e2.x - e1.x) > 80f) {
          togglePauseMenu()
          return true
        }
        return false
      }
    })

    edgeView.setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
    mLayout?.addView(edgeView, edgeParams)
  }

  override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
    if (event?.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
      togglePauseMenu()
      return true
    }
    if (event?.keyCode == KeyEvent.KEYCODE_BACK) {
      return true
    }
    return super.dispatchKeyEvent(event)
  }

  private fun togglePauseMenu() {
    val overlay = pauseMenuOverlay ?: return
    if (overlay.isShowing()) {
      nativeResumeEmulation()
      overlay.dismiss()
    } else {
      nativePauseEmulation()
      overlay.show()
    }
  }

  private fun setupOnScreenController() {
    // Create on-screen controller
    onScreenController = OnScreenController(this).apply {
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
    }

    // Create input bridge
    controllerBridge = ControllerInputBridge()
    onScreenController?.setControllerListener(controllerBridge!!)
    onScreenController?.onMenuButtonTapped = { togglePauseMenu() }

    // Add to layout
    mLayout?.addView(onScreenController)

    // Check for existing controllers and show/hide accordingly
    updateControllerVisibility()
  }

  override fun onResume() {
    super.onResume()
    if (suspendedByLifecycle) {
      nativeResumeEmulation()
      suspendedByLifecycle = false
    }

    mLayout?.postDelayed({
      registerVirtualController()
    }, 1000)

    val showFps = prefs.getBoolean("show_fps", true)
    fpsTextView?.visibility = if (showFps) View.VISIBLE else View.GONE
    if (showFps) {
      fpsHandler.postDelayed(fpsRunnable, fpsUpdateInterval)
    } else {
      fpsHandler.removeCallbacks(fpsRunnable)
    }
  }

  override fun onPause() {
    fpsHandler.removeCallbacks(fpsRunnable)
    suspendedByLifecycle = true
    nativePauseEmulation()
    super.onPause()
  }

  private fun registerVirtualController() {
    try {
      // Register the virtual on-screen controller as a joystick device
      // Device ID: -2, Name: "On-Screen Controller"
      org.libsdl.app.SDLControllerManager.nativeAddJoystick(
        -2, // device_id
        "On-Screen Controller", // name
        "Virtual touchscreen controller", // desc
        0x045e, // vendor_id (Microsoft)
        0x028e, // product_id (Xbox 360 Controller)
        false, // is_accelerometer
        0xFFFF, // button_mask (all buttons)
        6, // naxes (left X/Y, right X/Y, left trigger, right trigger)
        0x3F, // axis_mask (6 axes)
        0, // nhats
        0  // nballs
      )
      android.util.Log.d("MainActivity", "Virtual controller registered successfully")
    } catch (e: Exception) {
      android.util.Log.e("MainActivity", "Failed to register virtual controller: ${e.message}")
    }
  }

  private fun setupControllerDetection() {
    inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
    inputManager?.registerInputDeviceListener(this, null)
    
    // Check for already connected controllers
    checkForPhysicalControllers()
  }

  private fun checkForPhysicalControllers() {
    val deviceIds = inputManager?.inputDeviceIds ?: return
    hasPhysicalController = deviceIds.any { deviceId ->
      val device = inputManager?.getInputDevice(deviceId)
      isGameController(device)
    }
    updateControllerVisibility()
  }

  private fun isGameController(device: InputDevice?): Boolean {
    if (device == null) return false

    // Exclude virtual and built-in devices (sensors, accelerometers, etc.)
    if (device.isVirtual) return false
    val name = device.name?.lowercase() ?: ""
    if (name.contains("accelerometer") || name.contains("gyroscope") ||
        name.contains("sensor") || name.contains("gpio")) {
      return false
    }

    val sources = device.sources
    // Must have gamepad or joystick source AND have real axes/buttons
    val isGamepad = (sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
    val isJoystick = (sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    if (!isGamepad && !isJoystick) return false

    // Require at least one motion axis to filter out keyboard-only "gamepads"
    val motionRanges = device.motionRanges
    return motionRanges != null && motionRanges.isNotEmpty()
  }

  private fun updateControllerVisibility() {
    // Show on-screen controller only if no physical controller is connected
    val shouldShow = !hasPhysicalController
    
    if (shouldShow != isControllerVisible) {
      isControllerVisible = shouldShow
      onScreenController?.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }
  }

  // InputDeviceListener callbacks
  override fun onInputDeviceAdded(deviceId: Int) {
    val device = inputManager?.getInputDevice(deviceId)
    if (isGameController(device)) {
      hasPhysicalController = true
      updateControllerVisibility()
    }
  }

  override fun onInputDeviceRemoved(deviceId: Int) {
    // Recheck all devices to see if any controllers remain
    checkForPhysicalControllers()
  }

  override fun onInputDeviceChanged(deviceId: Int) {
    // Recheck all devices in case configuration changed
    checkForPhysicalControllers()
  }

  override fun onDestroy() {
    fpsHandler.removeCallbacks(fpsRunnable)

    // Unregister virtual controller
    try {
      org.libsdl.app.SDLControllerManager.nativeRemoveJoystick(-2)
    } catch (e: Exception) {
      android.util.Log.e("MainActivity", "Failed to unregister virtual controller: ${e.message}")
    }
    
    inputManager?.unregisterInputDeviceListener(this)
    super.onDestroy()
  }

  // Manual control methods (for settings/preferences)
  fun toggleOnScreenController() {
    isControllerVisible = !isControllerVisible
    onScreenController?.visibility = if (isControllerVisible) View.VISIBLE else View.GONE
  }

  fun showOnScreenController() {
    isControllerVisible = true
    onScreenController?.visibility = View.VISIBLE
  }

  fun hideOnScreenController() {
    isControllerVisible = false
    onScreenController?.visibility = View.GONE
  }

  fun forceUpdateControllerVisibility() {
    checkForPhysicalControllers()
  }

  override fun getLibraries(): Array<String> = arrayOf(
    "SDL2",
    "xemu",
  )
}
