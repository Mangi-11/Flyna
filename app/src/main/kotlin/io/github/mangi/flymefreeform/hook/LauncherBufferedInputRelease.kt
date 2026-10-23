package io.github.mangi.flymefreeform.hook

import android.view.MotionEvent
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.gesture.CornerBufferedInputRelease
import io.github.mangi.flymefreeform.gesture.CornerStreamIdentity
import io.github.mangi.flymefreeform.platform.coloros.ColorOsInputEventDisplay
import io.github.mangi.flymefreeform.platform.coloros.ColorOsLauncherInputTarget
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException

/** 候选需要输入时让宿主回注已有 DOWN，不手工构造事件或修改全局超时。 */
internal class LauncherBufferedInputRelease(private val target: ColorOsLauncherInputTarget) {
    private val lease = CornerBufferedInputRelease()
    private var helper = WeakReference<Any>(null)

    fun install(module: XposedModule): XposedInterface.HookHandle =
        module.hook(target.interceptMethod)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .setId("flymefreeform.launcher.buffered_input_observer")
            .intercept { chain ->
                val result = chain.proceed()
                val event = chain.getArg(0) as? MotionEvent
                val owner = chain.thisObject
                if (event != null && owner != null) synchronized(this) {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            if (event.pointerCount == 1) {
                                lease.begin(CornerStreamIdentity(event.deviceId, ColorOsInputEventDisplay.read(event),
                                    event.getPointerId(0), event.eventTime, event.rawX, event.rawY))
                                helper = WeakReference(owner)
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            lease.finish()
                            helper.clear()
                        }
                    }
                }
                result
            }

    @Synchronized
    fun release(identity: CornerStreamIdentity): Boolean {
        if (!lease.matches(identity)) return false
        val owner = helper.get() ?: return false
        if (!lease.request(identity)) return true
        try {
            target.releaseMethod.invoke(owner, false)
        } catch (exception: InvocationTargetException) {
            lease.finish()
            throw exception.targetException
        }
        return true
    }
}
