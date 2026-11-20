package io.github.mangi.flyna.platform.coloros

import android.os.Bundle
import android.os.Message
import android.os.Messenger
import io.github.mangi.flyna.BuildConfig
import io.github.mangi.flyna.gesture.CornerInputSession

/** 只传递角落会话语义，不跨进程传递 MotionEvent、窗口 token 或任意启动目标。 */
internal object CornerInputProtocol {
    const val ACTION = "${BuildConfig.APPLICATION_ID}.action.BIND_CORNER_INPUT_V1"
    const val PERMISSION = "android.permission.STATUS_BAR_SERVICE"
    const val SYSTEM_UI = "com.android.systemui"
    const val VERSION = 1
    const val READY = 1
    const val PREPARE = 2
    const val ACCEPT = 3
    const val REJECT = 4
    const val ACTIVATE = 5
    const val MOVE = 6
    const val UP = 7
    const val CANCEL = 8
    const val ID = "id"
    const val TIME = "time"
    const val X = "x"
    const val Y = "y"
    const val DISPLAY = "display"
    const val REPLY = "reply"
    const val MAX_EVENT_AGE_MS = CornerInputSession.DEFAULT_MAX_EVENT_AGE_MILLIS
    const val SESSION_TIMEOUT_MS = CornerInputSession.DEFAULT_TIMEOUT_MILLIS

    fun message(what: Int, id: Long = 0L, time: Long = 0L, x: Float = 0f, y: Float = 0f,
        reply: Messenger? = null): Message = Message.obtain().apply {
        this.what = what
        arg1 = VERSION
        replyTo = reply
        data = Bundle().apply {
            putLong(ID, id)
            putLong(TIME, time)
            putFloat(X, x)
            putFloat(Y, y)
            putInt(DISPLAY, 0)
        }
    }
}
