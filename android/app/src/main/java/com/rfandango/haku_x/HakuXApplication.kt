package com.rfandango.haku_x

import android.app.Application
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HakuXApplication : Application() {

  companion object {
    private const val TAG = "HakuXApp"
    private const val CURRENT_LOG = "current.log"
    private const val PREVIOUS_LOG = "previous.log"

    private val LOG_TAGS = arrayOf(
      // App
      "hakuX:V", "hakuX-phase:V", "hakuX-cpu:V", "hakuX-stall:V",
      "hakuX-rw:V", "hakuX-rpbrk:V", "hakuX-tex:V",
      "hakuX-mmio:V", "hakuX-nop:V", "hakuX-mhist:V",
      "xemu:V", "xemu-sfp:V", "xemu-surf:V", "xemu-vsync:V",
      "xemu-gpu:V", "xemu-pace:V", "xemu-work:V",
      "nv2a:V",
      // Native crash
      "DEBUG:V", "libc:V", "crash_dump:V", "tombstoned:V",
      // Memory / OOM
      "lowmemorykiller:V", "lmkd:V", "ActivityManager:W", "Zygote:W",
      // Runtime
      "AndroidRuntime:V", "art:W", "linker:V",
      // GPU
      "EGL:W", "Vulkan:V",
      // Silence everything else
      "*:S"
    )

    @Volatile
    private var logProcess: Process? = null

    fun getLogFile(context: android.content.Context, name: String): File =
      File(context.filesDir, name)

    fun currentLogFile(context: android.content.Context): File =
      getLogFile(context, CURRENT_LOG)

    fun previousLogFile(context: android.content.Context): File =
      getLogFile(context, PREVIOUS_LOG)
  }

  override fun onCreate() {
    super.onCreate()
    rotateLogs()
    startLogCapture()
  }

  private fun rotateLogs() {
    val current = currentLogFile(this)
    val previous = previousLogFile(this)

    if (current.exists() && current.length() > 0) {
      previous.delete()
      current.renameTo(previous)
      Log.i(TAG, "Rotated logs: current -> previous (${previous.length()} bytes)")
    }
  }

  private fun startLogCapture() {
    Thread {
      try {
        // Clear logcat buffer so we only capture this session
        Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()

        // Write session separator header
        val current = currentLogFile(this)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        FileOutputStream(current, false).use { fos ->
          fos.write("========================================\n".toByteArray())
          fos.write("=== Session started: $timestamp\n".toByteArray())
          fos.write("=== PID: ${android.os.Process.myPid()}\n".toByteArray())
          fos.write("=== Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n".toByteArray())
          fos.write("=== Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})\n".toByteArray())
          fos.write("========================================\n\n".toByteArray())
        }

        // Start logcat with tag filter, appending to the file with the header
        val cmd = mutableListOf("logcat", "-v", "threadtime")
        cmd.addAll(LOG_TAGS)
        val process = Runtime.getRuntime().exec(cmd.toTypedArray())
        logProcess = process

        FileOutputStream(current, true).use { fos ->
          process.inputStream.copyTo(fos)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Log capture failed", e)
      }
    }.apply {
      isDaemon = true
      name = "log-capture"
      start()
    }
  }
}
