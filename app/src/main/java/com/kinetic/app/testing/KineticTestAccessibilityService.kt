package com.kinetic.app.testing

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor

/**
 * Explicitly opt-in, on-device UI test bridge.
 * The service is restricted to Kinetic's own package in the accessibility config.
 * Nothing is uploaded or exposed over the network by this class.
 */
class KineticTestAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        publishSnapshot("Kinetic test bridge connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != packageName) return
        publishSnapshot()
    }

    override fun onInterrupt() {
        latestSnapshot = "Accessibility service interrupted"
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun publishSnapshot(prefix: String? = null) {
        val root = rootInActiveWindow
        val lines = ArrayList<String>(48)
        if (prefix != null) lines += prefix
        if (root == null) {
            lines += "No active Kinetic window"
        } else {
            lines += "Screen: ${root.className ?: "unknown"}"
            appendNode(root, lines, 0, intArrayOf(0))
        }
        latestSnapshot = lines.take(90).joinToString("\n")
    }

    private fun appendNode(
        node: AccessibilityNodeInfo,
        lines: MutableList<String>,
        depth: Int,
        count: IntArray
    ) {
        if (depth > 12 || count[0] >= 80) return
        count[0]++
        val indent = "  ".repeat(depth.coerceAtMost(8))
        val label = when {
            node.isPassword -> "[password field]"
            !node.text.isNullOrBlank() -> node.text.toString().take(100)
            !node.contentDescription.isNullOrBlank() -> node.contentDescription.toString().take(100)
            else -> ""
        }
        val id = node.viewIdResourceName?.substringAfterLast('/')?.take(48).orEmpty()
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        lines += "$indent${node.className?.toString()?.substringAfterLast('.') ?: "View"}" +
            (if (id.isNotBlank()) " #$id" else "") +
            (if (label.isNotBlank()) " — $label" else "") +
            " [${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]"
        for (i in 0 until node.childCount) {
            if (count[0] >= 80) break
            val child = node.getChild(i) ?: continue
            appendNode(child, lines, depth + 1, count)
        }
    }

    private fun tap(x: Float, y: Float, durationMs: Long, done: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(40, 600)))
            .build()
        done(dispatchGesture(gesture, null, null))
    }

    private fun capture(done: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            done(null)
            return
        }
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val hardware = result.hardwareBuffer
                try {
                    val wrapped = Bitmap.wrapHardwareBuffer(hardware, result.colorSpace)
                    val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    wrapped?.recycle()
                    if (bitmap == null) {
                        done(null)
                        return
                    }
                    val out = File(cacheDir, "kinetic-test-screen.png")
                    FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    latestScreenshotPath = out.absolutePath
                    done(out.absolutePath)
                } catch (_: Exception) {
                    done(null)
                } finally {
                    hardware.close()
                }
            }

            override fun onFailure(errorCode: Int) {
                done(null)
            }
        })
    }

    companion object {
        @Volatile var latestSnapshot: String = "Test bridge is not enabled"
            private set
        @Volatile var latestScreenshotPath: String? = null
            private set
        @Volatile private var instance: KineticTestAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        fun refreshSnapshot(): String {
            val service = instance
            if (service != null) service.publishSnapshot()
            return latestSnapshot
        }

        fun captureCurrentScreen(done: (String?) -> Unit) {
            val service = instance
            if (service == null) {
                done(null)
            } else {
                service.capture { path -> Handler(Looper.getMainLooper()).post { done(path) } }
            }
        }

        fun tap(x: Float, y: Float, done: (Boolean) -> Unit) {
            val service = instance
            if (service == null) done(false) else service.tap(x, y, 90, done)
        }
    }
}
