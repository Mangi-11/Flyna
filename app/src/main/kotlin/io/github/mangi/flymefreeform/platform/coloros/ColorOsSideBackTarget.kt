package io.github.mangi.flymefreeform.platform.coloros

import android.view.MotionEvent
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 侧返回缓存 DOWN 后的局部取消入口；由宿主回注并完成返回动画状态清理。 */
internal class ColorOsSideBackTarget private constructor(
    val motionMethod: Method,
    private val cancelMethod: Method,
    private val immediateMethod: Method,
    private val allowGesture: Field,
    private val withinInsets: Field,
    private val thresholdCrossed: Field,
) {
    fun isBuffering(owner: Any): Boolean =
        allowGesture.getBoolean(owner) && withinInsets.getBoolean(owner) &&
            !thresholdCrossed.getBoolean(owner) && invoke(immediateMethod, owner) == false

    fun releaseBufferedDown(owner: Any, event: MotionEvent) {
        // false：普通触摸；true：放行宿主缓存。不能自行构造或重复注入 DOWN。
        invoke(cancelMethod, owner, event, false, true)
    }

    private fun invoke(method: Method, owner: Any, vararg args: Any): Any? = try {
        method.invoke(owner, *args)
    } catch (exception: InvocationTargetException) {
        throw exception.targetException
    }

    companion object {
        fun resolve(classLoader: ClassLoader): ColorOsSideBackTarget {
            val type = Class.forName(
                "com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector",
                false, classLoader,
            )
            fun method(name: String, result: Class<*>, vararg params: Class<*>): Method =
                type.getDeclaredMethod(name, *params).apply {
                    if (returnType != result || Modifier.isStatic(modifiers)) {
                        throw NoSuchMethodException("Side back method signature mismatch: $name")
                    }
                    isAccessible = true
                }
            fun flag(name: String): Field = type.getDeclaredField(name).apply {
                if (this.type != Boolean::class.javaPrimitiveType || Modifier.isStatic(modifiers)) {
                    throw NoSuchFieldException("Side back field signature mismatch: $name")
                }
                isAccessible = true
            }
            return ColorOsSideBackTarget(
                motionMethod = method("onMotionEventImpl", Void.TYPE, MotionEvent::class.java, Map::class.java),
                cancelMethod = method("cancelGesture", Void.TYPE, MotionEvent::class.java,
                    Boolean::class.javaObjectType, Boolean::class.javaObjectType),
                immediateMethod = method("shouldInjectImmediate", Boolean::class.javaPrimitiveType!!),
                allowGesture = flag("mAllowGesture"),
                withinInsets = flag("mIsWithinInsets"),
                thresholdCrossed = flag("mThresholdCrossed"),
            )
        }
    }
}
