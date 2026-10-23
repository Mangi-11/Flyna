package io.github.mangi.flymefreeform.platform.coloros

import android.view.MotionEvent
import android.view.View
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** 仅定位导航横条的视觉输入入口，不接管系统侧返或全局输入分发。 */
internal object ColorOsNavigationFeedbackTarget {
    fun resolve(classLoader: ClassLoader): Method {
        val type = classLoader.loadClass(
            "com.oplus.systemui.navigationbar.gesture.sidegesture.OplusNavigationHandle",
        )
        if (!View::class.java.isAssignableFrom(type)) {
            throw NoSuchMethodException("Navigation feedback owner is not a View")
        }
        return type.getDeclaredMethod("handleValidTouchEvent", MotionEvent::class.java).apply {
            if (returnType != Void.TYPE || Modifier.isStatic(modifiers)) {
                throw NoSuchMethodException("Navigation feedback signature mismatch")
            }
            isAccessible = true
        }
    }
}
