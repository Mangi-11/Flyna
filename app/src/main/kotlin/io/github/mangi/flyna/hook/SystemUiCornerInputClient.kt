package io.github.mangi.flyna.hook

import android.app.BroadcastOptions
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import io.github.mangi.flyna.platform.coloros.CornerInputProtocol

/** SystemUI 的单会话客户端；主线程持有状态，Messenger 仅发送有界的单向消息。 */
internal class SystemUiCornerInputClient(
    private val context: Context,
    private val handler: Handler,
    private val log: (Int, String, Throwable?) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onReady: () -> Unit = {},
) {
    private var started = false
    private var remote: Messenger? = null
    private var death: IBinder.DeathRecipient? = null
    private var current: Session? = null
    private var nextId = SystemClock.uptimeMillis().coerceAtLeast(1L)
    private var lastConnectionAt = -RECONNECT_INTERVAL_MS
    private var lastFailureAt = -RECONNECT_INTERVAL_MS
    private var bootstrapAttempts = 0
    private var pendingMove: Sample? = null
    private var movePosted = false
    private val inbox = Messenger(Handler(handler.looper) { message ->
        try { receive(message) }
        catch (exception: RuntimeException) { report("CORNER_INPUT_REPLY_INVALID", exception) }
        true
    })
    private val bootstrap = Runnable { sendBootstrap() }
    private val choreographer = Choreographer.getInstance()
    private val moveFrame = Choreographer.FrameCallback { flushMove() }
    private val prepareTimeout = Runnable {
        current?.takeUnless { it.prepared }?.let {
            report("CORNER_INPUT_PREPARE_TIMEOUT", null)
            cancel(it.id)
        }
    }
    private val sessionTimeout = Runnable {
        current?.let {
            cancel(it.id)
            notifyDisconnected()
        }
    }

    val isReady: Boolean
        get() = remote?.binder?.isBinderAlive == true

    fun start() {
        checkThread()
        if (started) return
        started = true
        requestConnection()
    }

    /** 仅在启动或新手势需要时调用；失败不产生常驻重试。 */
    fun requestConnection() {
        checkThread()
        if (!started || isReady) return
        val now = SystemClock.uptimeMillis()
        if (now - lastConnectionAt < RECONNECT_INTERVAL_MS) return
        lastConnectionAt = now
        bootstrapAttempts = 0
        handler.removeCallbacks(bootstrap)
        sendBootstrap()
    }

    fun begin(x: Float, y: Float, onPrepared: (Boolean) -> Unit): Long {
        checkThread()
        if (!isReady) { requestConnection(); return 0L }
        current?.let { cancel(it.id) }
        val id = nextId
        nextId = if (nextId == Long.MAX_VALUE) 1L else nextId + 1L
        val session = Session(id, onPrepared)
        current = session
        handler.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS)
        handler.postDelayed(sessionTimeout, CornerInputProtocol.SESSION_TIMEOUT_MS)
        // 回注 DOWN 保留旧时间；准备请求的有效期从本次发送开始，不消耗系统仲裁等待时间。
        if (!send(CornerInputProtocol.PREPARE, id, SystemClock.uptimeMillis(), x, y)) return 0L
        return id
    }

    fun activate(id: Long, x: Float, y: Float, time: Long): Boolean {
        checkThread()
        val session = current?.takeIf { it.id == id && it.prepared } ?: return false
        if (session.activated) return false
        if (!send(CornerInputProtocol.ACTIVATE, id, time, x, y)) return false
        session.activated = true
        return true
    }

    fun move(id: Long, x: Float, y: Float, time: Long) {
        checkThread()
        if (current?.let { it.id == id && it.activated } != true) return
        pendingMove = Sample(id, time, x, y)
        if (!movePosted) {
            movePosted = true
            choreographer.postFrameCallback(moveFrame)
        }
    }

    fun finish(id: Long, x: Float, y: Float, time: Long) {
        checkThread()
        val session = current?.takeIf { it.id == id } ?: return
        if (!session.prepared || !session.activated) { cancel(id); return }
        flushMove()
        if (current !== session) return
        clearSession()
        send(CornerInputProtocol.UP, id, time, x, y)
    }

    fun cancel(id: Long) {
        checkThread()
        if (current?.id != id) return
        clearSession()
        send(CornerInputProtocol.CANCEL, id)
    }

    fun close() {
        checkThread()
        current?.let { cancel(it.id) }
        started = false
        handler.removeCallbacks(bootstrap)
        disconnect(notify = false)
    }

    private fun sendBootstrap() {
        if (!started || isReady || bootstrapAttempts >= MAX_BOOTSTRAP_ATTEMPTS) return
        bootstrapAttempts++
        try {
            val intent = Intent(CornerInputProtocol.ACTION).setPackage("android")
                .putExtra(CornerInputProtocol.REPLY, inbox)
            val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
            context.sendBroadcast(intent, CornerInputProtocol.PERMISSION, options)
        } catch (exception: RuntimeException) {
            report("CORNER_INPUT_BOOTSTRAP_FAILED", exception)
        }
        if (bootstrapAttempts < MAX_BOOTSTRAP_ATTEMPTS) {
            handler.postDelayed(bootstrap, BOOTSTRAP_INTERVAL_MS)
        }
    }

    private fun receive(message: Message) {
        if (!started || message.sendingUid != Process.SYSTEM_UID ||
            message.arg1 != CornerInputProtocol.VERSION) return
        if (message.what == CornerInputProtocol.READY) {
            message.replyTo?.let(::connected)
            return
        }
        val session = current ?: return
        if (message.peekData()?.getLong(CornerInputProtocol.ID) != session.id) return
        when (message.what) {
            CornerInputProtocol.ACCEPT -> {
                if (session.prepared) return
                session.prepared = true
                handler.removeCallbacks(prepareTimeout)
                completePreparation(session, true)
            }
            CornerInputProtocol.REJECT -> {
                val wasPrepared = session.prepared
                clearSession()
                if (wasPrepared) notifyDisconnected()
            }
        }
    }

    private fun connected(peer: Messenger) {
        if (remote?.binder == peer.binder && isReady) return
        disconnect(notify = remote != null)
        val recipient = IBinder.DeathRecipient {
            handler.post { if (remote?.binder == peer.binder) disconnect(notify = true) }
        }
        try {
            peer.binder.linkToDeath(recipient, 0)
            remote = peer
            death = recipient
            handler.removeCallbacks(bootstrap)
            onReady()
        } catch (exception: RemoteException) {
            report("CORNER_INPUT_CONNECTION_FAILED", exception)
        }
    }

    private fun send(what: Int, id: Long, time: Long = 0L, x: Float = 0f, y: Float = 0f): Boolean {
        val peer = remote ?: return false
        val sentAt = SystemClock.uptimeMillis()
        if (time > sentAt) return false
        return try {
            // 协议时间用于传输时效与顺序；物理事件时间只在本进程识别和回注去重中使用。
            peer.send(CornerInputProtocol.message(what, id, sentAt, x, y))
            true
        } catch (exception: RemoteException) {
            report("CORNER_INPUT_SEND_FAILED", exception)
            disconnect(notify = true)
            false
        } catch (exception: RuntimeException) {
            report("CORNER_INPUT_SEND_FAILED", exception)
            disconnect(notify = true)
            false
        }
    }

    private fun flushMove() {
        choreographer.removeFrameCallback(moveFrame)
        movePosted = false
        val sample = pendingMove ?: return
        pendingMove = null
        if (current?.let { it.id == sample.id && it.activated } == true) {
            send(CornerInputProtocol.MOVE, sample.id, sample.time, sample.x, sample.y)
        }
    }

    private fun clearSession() {
        val session = current
        current = null
        handler.removeCallbacks(prepareTimeout)
        handler.removeCallbacks(sessionTimeout)
        choreographer.removeFrameCallback(moveFrame)
        movePosted = false
        pendingMove = null
        if (session != null) completePreparation(session, false)
    }

    private fun completePreparation(session: Session, accepted: Boolean) {
        val callback = session.onPrepared ?: return
        session.onPrepared = null
        val notify = Runnable {
            try { callback(accepted && current === session) }
            catch (exception: RuntimeException) { report("CORNER_INPUT_PREPARED_CALLBACK_FAILED", exception) }
        }
        // ACCEPT 已由同一 Looper 的 Messenger 分发，立即确认可避免接管前再次排队。
        // 失败可能发生在 begin/send 内，仍延后通知，避免重入尚未完成赋值的调用方。
        if (accepted) notify.run() else handler.post(notify)
    }

    private fun disconnect(notify: Boolean) {
        val peer = remote
        val recipient = death
        remote = null
        death = null
        clearSession()
        if (peer != null && recipient != null) {
            try { peer.binder.unlinkToDeath(recipient, 0) }
            catch (exception: RuntimeException) { report("CORNER_INPUT_UNLINK_FAILED", exception) }
        }
        if (notify) notifyDisconnected()
    }

    private fun notifyDisconnected() {
        handler.post {
            // 旧连接/会话的排队通知不能清掉之后已开始的新手势。
            try { if (current == null) onDisconnected() }
            catch (exception: RuntimeException) { report("CORNER_INPUT_DISCONNECT_CALLBACK_FAILED", exception) }
        }
    }

    private fun report(code: String, exception: Throwable?) {
        val now = SystemClock.uptimeMillis()
        if (now - lastFailureAt < RECONNECT_INTERVAL_MS) return
        lastFailureAt = now
        log(Log.WARN, code, exception)
    }

    private fun checkThread() = check(Looper.myLooper() == handler.looper)

    private class Session(val id: Long, var onPrepared: ((Boolean) -> Unit)?) {
        var prepared = false
        var activated = false
    }

    private data class Sample(val id: Long, val time: Long, val x: Float, val y: Float)

    private companion object {
        const val PREPARE_TIMEOUT_MS = 250L
        const val BOOTSTRAP_INTERVAL_MS = 1_000L
        const val MAX_BOOTSTRAP_ATTEMPTS = 3
        const val RECONNECT_INTERVAL_MS = 5_000L
    }
}
