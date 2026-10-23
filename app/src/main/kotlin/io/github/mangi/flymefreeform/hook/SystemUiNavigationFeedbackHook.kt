package io.github.mangi.flymefreeform.hook

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.gesture.AdaptiveCornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerReleaseHistory
import io.github.mangi.flymefreeform.gesture.CornerSide
import io.github.mangi.flymefreeform.gesture.CornerStreamIdentity
import io.github.mangi.flymefreeform.gesture.CornerStreamTracker
import io.github.mangi.flymefreeform.platform.DisplayCornerGeometry
import io.github.mangi.flymefreeform.gesture.CornerVisualGate
import io.github.mangi.flymefreeform.platform.coloros.ColorOsInputEventDisplay
import io.github.mangi.flymefreeform.platform.coloros.ColorOsNavigationFeedbackTarget
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 延后角落候选的横条跟手；输入抢占和菜单始终由独立 SPY 所有者处理。 */
internal class SystemUiNavigationFeedbackHook(
    context: Context,
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
    private val canSuppress: (CornerSide) -> Boolean,
) {
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val installationStarted = AtomicBoolean(false)
    private val states = WeakHashMap<View, FeedbackState>()
    private val releases = CornerReleaseHistory()
    private var installed = false
    private var lastFailureAt = -FAILURE_LOG_INTERVAL_MS

    fun install(classLoader: ClassLoader): Boolean {
        if (!installationStarted.compareAndSet(false, true)) return installed
        try {
            val target = ColorOsNavigationFeedbackTarget.resolve(classLoader)
            module.hook(target)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flymefreeform.systemui.navigation_feedback")
                .intercept { chain ->
                    val event = chain.getArg(0) as? MotionEvent
                        ?: return@intercept chain.proceed()
                    val view = chain.thisObject as? View
                        ?: return@intercept chain.proceed()
                    // 已验证目标由 SystemUI 主线程调用；其他线程一律保留原行为。
                    if (Looper.myLooper() != Looper.getMainLooper()) return@intercept chain.proceed()
                    val suppress = try {
                        shouldSuppress(view, event)
                    } catch (exception: RuntimeException) {
                        states.remove(view)
                        logFailure("SYSTEMUI_NAVIGATION_FEEDBACK_FAILED", exception)
                        false
                    }
                    // 原方法异常不能被兼容分支吞掉或触发第二次执行。
                    if (suppress) null else chain.proceed()
                }
            installed = true
            module.log(Log.INFO, TAG, "SYSTEMUI_NAVIGATION_FEEDBACK_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            logFailure("SYSTEMUI_NAVIGATION_FEEDBACK_TARGET_UNAVAILABLE", exception)
        } catch (exception: IllegalArgumentException) {
            logFailure("SYSTEMUI_NAVIGATION_FEEDBACK_HOOK_REJECTED", exception)
        } catch (exception: LinkageError) {
            logFailure("SYSTEMUI_NAVIGATION_FEEDBACK_TARGET_LINKAGE_FAILED", exception)
        }
        return installed
    }

    fun releaseUnclaimed(identity: CornerStreamIdentity) {
        check(Looper.myLooper() == Looper.getMainLooper())
        releases.record(identity, SystemClock.uptimeMillis())
        states.values.filter { it.stream.matches(identity) }.forEach { it.gate.releaseUnclaimed() }
    }

    private fun shouldSuppress(view: View, event: MotionEvent): Boolean {
        val state = if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            states.getOrPut(view) { FeedbackState() }
        } else {
            states[view] ?: return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val newStream = state.stream.begin(
                    event.deviceId, ColorOsInputEventDisplay.read(event), event.getPointerId(0),
                    event.eventTime, event.rawX, event.rawY,
                )
                // 厂商回注可改变 downTime；同一物理起点不重开本地候选。
                if (!newStream) return false
                state.gate.finish()
                if (state.stream.identity?.let { releases.contains(it, SystemClock.uptimeMillis()) } == true) return false
                val config = createConfig(view, event) ?: return false
                state.gate.down(event.rawX, event.rawY, config)
                // DOWN 保留原生 detector 初始化；不缓存 Chain 或 MotionEvent。
            }
            MotionEvent.ACTION_MOVE -> {
                if (!state.stream.matches(event.deviceId, ColorOsInputEventDisplay.read(event))) return false
                return state.gate.defersMoves
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                state.gate.finish()
                state.stream.finish()
                states.remove(view)
            }
        }
        // 终止事件始终交原链，清理长按消息、速度追踪与横条内部动画状态。
        return false
    }

    private fun createConfig(view: View, event: MotionEvent): CornerGestureConfig? {
        if (!configuration.isAvailable || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return null
        if (event.pointerCount != 1 || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return null
        val display = view.display ?: return null
        if (display.displayId != Display.DEFAULT_DISPLAY || ColorOsInputEventDisplay.read(event) != display.displayId) return null
        val settings = configuration.snapshot
        if (!settings.enabled) return null
        // 原生长按功能以横条自身 X 范围判定；与该范围相交时不延后 MOVE。
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        if (event.rawX >= location[0] && event.rawX <= location[0] + view.width) return null
        val region = DisplayCornerGeometry.read(view.context, settings.cornerArcLengthDp, settings.cornerArcWidthDp) ?: return null
        return AdaptiveCornerGestureConfig.create(
            triggerRegion = region,
            touchSlop = touchSlop,
            density = view.resources.displayMetrics.density,
            leftEnabled = settings.leftCornerEnabled && canSuppress(CornerSide.Left),
            rightEnabled = settings.rightCornerEnabled && canSuppress(CornerSide.Right),
        )
    }

    private fun logFailure(code: String, exception: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureAt < FAILURE_LOG_INTERVAL_MS) return
        lastFailureAt = now
        module.log(Log.WARN, TAG, code, exception)
    }

    private class FeedbackState {
        val gate = CornerVisualGate()
        val stream = CornerStreamTracker()
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val FAILURE_LOG_INTERVAL_MS = 10_000L
    }
}
