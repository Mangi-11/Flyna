package io.github.mangi.flyna.platform.coloros

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 已验证桌面普通 View 的翻页边界；不访问滚动字段或修改动画状态。 */
internal data class ColorOsLauncherWorkspaceTarget(
    val dispatchTouchEvent: Method,
    val scrollingStarts: List<Method>,
) {
    companion object {
        const val WORKSPACE_CLASS = "com.android.launcher3.OplusWorkspace"

        @SuppressLint("PrivateApi")
        fun resolve(loader: ClassLoader): ColorOsLauncherWorkspaceTarget {
            val workspace = loader.loadClass(WORKSPACE_CLASS)
            require(View::class.java.isAssignableFrom(workspace))
            val dispatch = workspace.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
            val starts = listOf(
                workspace.getDeclaredMethod(
                    "determineScrollingStart", MotionEvent::class.java, Float::class.javaPrimitiveType,
                ),
                workspace.getDeclaredMethod(
                    "determineScrollingStart", MotionEvent::class.java,
                    Float::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                ),
            )
            require(dispatch.returnType == Boolean::class.javaPrimitiveType)
            require(starts.all { it.returnType == Void.TYPE })
            (starts + dispatch).forEach { method ->
                require(!Modifier.isStatic(method.modifiers) && !Modifier.isAbstract(method.modifiers))
                method.isAccessible = true
            }
            return ColorOsLauncherWorkspaceTarget(dispatch, starts)
        }
    }
}
