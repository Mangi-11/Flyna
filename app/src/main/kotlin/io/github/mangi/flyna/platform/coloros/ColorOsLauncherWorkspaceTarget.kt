package io.github.mangi.flyna.platform.coloros

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 已验证桌面普通 View 的翻页边界；不访问滚动字段或修改动画状态。 */
internal data class ColorOsLauncherWorkspaceTarget(
    val dispatchTouchEvent: Method,
    val scrollingStarts: List<ScrollingStart>,
) {
    data class ScrollingStart(val method: Method, val eventIndex: Int)

    companion object {
        const val WORKSPACE_CLASS = "com.android.launcher3.OplusWorkspace"

        @SuppressLint("PrivateApi")
        fun resolve(loader: ClassLoader): ColorOsLauncherWorkspaceTarget {
            val workspace = loader.loadClass(WORKSPACE_CLASS)
            require(View::class.java.isAssignableFrom(workspace))
            val dispatch = workspace.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            val eventType = MotionEvent::class.java
            val floatType = Float::class.javaPrimitiveType!!
            val booleanType = Boolean::class.javaPrimitiveType!!
            // 两代桌面的重载成对出现。新版把带阈值入口的 MotionEvent 移到了第二个参数。
            val starts = try {
                listOf(
                    workspace.getDeclaredMethod("determineScrollingStart", eventType),
                    workspace.getDeclaredMethod("determineScrollingStart", floatType, eventType),
                )
            } catch (_: NoSuchMethodException) {
                listOf(
                    workspace.getDeclaredMethod("determineScrollingStart", eventType, floatType),
                    workspace.getDeclaredMethod("determineScrollingStart", eventType, floatType, booleanType),
                )
            }
            require(dispatch.returnType == Boolean::class.javaPrimitiveType)
            require(starts.all { it.returnType == Void.TYPE })
            (starts + dispatch).forEach { method ->
                require(!Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers))
                method.isAccessible = true
            }
            return ColorOsLauncherWorkspaceTarget(dispatch, starts.map { method ->
                ScrollingStart(method, method.parameterTypes.indexOf(eventType))
            })
        }
    }
}
