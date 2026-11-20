package io.github.mangi.flyna.hook

import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flyna.gesture.CornerSideBackHandoff
import io.github.mangi.flyna.gesture.CornerStreamIdentity
import io.github.mangi.flyna.gesture.CornerTriggerRegion
import io.github.mangi.flyna.platform.coloros.ColorOsInputEventDisplay
import io.github.mangi.flyna.platform.coloros.ColorOsSideBackTarget
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** GestureUI 线程只让出侧返回缓存，输入所有权仍由主线程的 SPY 窗口取得。 */
internal class SystemUiSideBackHook(
    private val module: XposedModule,
    private val environmentAllowed: () -> Boolean,
) {
    private val installationStarted = AtomicBoolean(false)
    private val states = WeakHashMap<Any, CornerSideBackHandoff>()
    @Volatile private var eligibility: Eligibility? = null
    private var lastFailureAt = -LOG_INTERVAL_MS
    private var lastReleaseAt = -LOG_INTERVAL_MS

    fun update(region: CornerTriggerRegion?, leftEnabled: Boolean, rightEnabled: Boolean) {
        // 由输入所有者一次发布；GestureUI 不读取主线程的窗口集合，也不等待主线程。
        eligibility = region?.let { Eligibility(it, leftEnabled, rightEnabled) }
    }

    fun install(classLoader: ClassLoader) {
        if (!installationStarted.compareAndSet(false, true)) return
        try {
            val target = ColorOsSideBackTarget.resolve(classLoader)
            module.hook(target.motionMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flyna.systemui.side_back_handoff")
                .intercept { chain ->
                    // 先且只执行一次原方法，让宿主识别回注、初始化本次流并保存原始 DOWN。
                    val result = chain.proceed()
                    val event = chain.getArg(0) as? MotionEvent ?: return@intercept result
                    val owner = chain.thisObject ?: return@intercept result
                    if (event.actionMasked != MotionEvent.ACTION_DOWN || event.pointerCount != 1 ||
                        !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) ||
                        event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return@intercept result
                    try {
                        val identity = CornerStreamIdentity(event.deviceId, ColorOsInputEventDisplay.read(event),
                            event.getPointerId(0), event.eventTime, event.rawX, event.rawY)
                        val ready = eligibility?.takeIf { environmentAllowed() }
                        val shouldRelease = synchronized(states) {
                            states.getOrPut(owner) { CornerSideBackHandoff() }.request(
                                identity, ready?.region, ready?.leftEnabled == true, ready?.rightEnabled == true,
                                nativeBuffering = ready != null && target.isBuffering(owner),
                            )
                        }
                        if (shouldRelease) {
                            Trace.beginSection("Flyna#sideBackRelease")
                            try { target.releaseBufferedDown(owner, event) }
                            finally { Trace.endSection() }
                            reportRelease()
                        }
                    } catch (exception: ReflectiveOperationException) {
                        reportFailure("SYSTEMUI_SIDE_BACK_RELEASE_FAILED", exception)
                    } catch (exception: RuntimeException) {
                        reportFailure("SYSTEMUI_SIDE_BACK_RELEASE_FAILED", exception)
                    }
                    result
                }
            module.log(Log.INFO, TAG, "SYSTEMUI_SIDE_BACK_HANDOFF_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            reportFailure("SYSTEMUI_SIDE_BACK_TARGET_UNAVAILABLE", exception)
        } catch (exception: IllegalArgumentException) {
            reportFailure("SYSTEMUI_SIDE_BACK_HOOK_REJECTED", exception)
        } catch (exception: LinkageError) {
            reportFailure("SYSTEMUI_SIDE_BACK_TARGET_LINKAGE_FAILED", exception)
        }
    }

    @Synchronized
    private fun reportRelease() {
        val now = SystemClock.uptimeMillis()
        if (now - lastReleaseAt < LOG_INTERVAL_MS) return
        lastReleaseAt = now
        module.log(Log.INFO, TAG, "SYSTEMUI_SIDE_BACK_DOWN_RELEASED")
    }

    @Synchronized
    private fun reportFailure(code: String, exception: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureAt < LOG_INTERVAL_MS) return
        lastFailureAt = now
        module.log(Log.WARN, TAG, code, exception)
    }

    private data class Eligibility(val region: CornerTriggerRegion, val leftEnabled: Boolean, val rightEnabled: Boolean)

    private companion object {
        const val TAG = "Flyna"
        const val LOG_INTERVAL_MS = 5_000L
    }
}
