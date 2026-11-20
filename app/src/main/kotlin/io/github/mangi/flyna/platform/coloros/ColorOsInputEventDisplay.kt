package io.github.mangi.flyna.platform.coloros

import android.annotation.SuppressLint
import android.view.InputEvent

/** 当前平台的输入显示标识未进入 SDK 桩；无法读取时拒绝参与该事件。 */
@SuppressLint("PrivateApi")
internal object ColorOsInputEventDisplay {
    private val method = try {
        InputEvent::class.java.getMethod("getDisplayId")
    } catch (_: NoSuchMethodException) { null }

    val isAvailable: Boolean get() = method != null

    fun read(event: InputEvent): Int = try {
        method?.invoke(event) as? Int ?: -1
    } catch (_: ReflectiveOperationException) { -1 }
}
