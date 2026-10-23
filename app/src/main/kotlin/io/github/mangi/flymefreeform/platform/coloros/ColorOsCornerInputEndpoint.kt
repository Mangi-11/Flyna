package io.github.mangi.flymefreeform.platform.coloros

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import io.github.mangi.flymefreeform.gesture.CornerInputSession
import io.github.mangi.flymefreeform.gesture.CornerSide

/** system_server 的会话接收端；广播只交换 Binder，后续每条消息再次验证发送 UID。 */
internal class ColorOsCornerInputEndpoint(
    private val context: Context,
    private val handler: Handler,
    private val prepare: (Float, Float) -> CornerSide?,
    private val deliver: (Int, CornerSide, Float, Float) -> Unit,
    private val cancel: () -> Unit,
    private val log: (Int, String, Throwable?) -> Unit,
) {
    private var expectedUid = -1
    private var client: Messenger? = null
    private var death: IBinder.DeathRecipient? = null
    private val session = CornerInputSession(
        CornerInputProtocol.SESSION_TIMEOUT_MS, CornerInputProtocol.MAX_EVENT_AGE_MS,
    )
    private var side: CornerSide? = null
    private var registered = false
    private val inbox = Messenger(Handler(handler.looper) { message -> receive(message); true })
    private val expiry = Runnable { invalidate() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != CornerInputProtocol.ACTION || sentFromUid != expectedUid ||
                sentFromPackage != CornerInputProtocol.SYSTEM_UI || expectedUid < 0) return
            val peer = intent.getParcelableExtra(CornerInputProtocol.REPLY, Messenger::class.java) ?: return
            bind(peer)
        }
    }

    fun start() {
        if (registered) return
        try {
            expectedUid = context.packageManager.getApplicationInfo(
                CornerInputProtocol.SYSTEM_UI, PackageManager.ApplicationInfoFlags.of(0),
            ).uid
            context.registerReceiver(receiver, IntentFilter(CornerInputProtocol.ACTION),
                CornerInputProtocol.PERMISSION, handler, Context.RECEIVER_EXPORTED)
            registered = true
        } catch (exception: RuntimeException) {
            log(Log.WARN, "CORNER_INPUT_ENDPOINT_UNAVAILABLE", exception)
        } catch (exception: PackageManager.NameNotFoundException) {
            log(Log.WARN, "CORNER_INPUT_SYSTEMUI_UNAVAILABLE", exception)
        }
    }

    private fun bind(peer: Messenger) {
        if (client?.binder == peer.binder && peer.binder.isBinderAlive) {
            try { peer.send(CornerInputProtocol.message(CornerInputProtocol.READY, reply = inbox)) }
            catch (exception: RemoteException) {
                disconnect()
                log(Log.WARN, "CORNER_INPUT_BIND_FAILED", exception)
            }
            return
        }
        disconnect()
        val recipient = IBinder.DeathRecipient { handler.post { if (client?.binder == peer.binder) disconnect() } }
        try {
            peer.binder.linkToDeath(recipient, 0)
            client = peer
            death = recipient
            peer.send(CornerInputProtocol.message(CornerInputProtocol.READY, reply = inbox))
        } catch (exception: RemoteException) {
            disconnect()
            log(Log.WARN, "CORNER_INPUT_BIND_FAILED", exception)
        }
    }

    private fun receive(message: Message) {
        try { receiveValidated(message) }
        catch (exception: RuntimeException) {
            invalidate()
            log(Log.WARN, "CORNER_INPUT_MESSAGE_FAILED", exception)
        }
    }

    private fun receiveValidated(message: Message) {
        if (message.sendingUid != expectedUid || expectedUid < 0 || client == null ||
            message.arg1 != CornerInputProtocol.VERSION) return
        val now = SystemClock.uptimeMillis()
        val expired = session.expire(now)
        if (expired != 0L) {
            side = null
            handler.removeCallbacks(expiry)
            cancelSafely()
            reply(CornerInputProtocol.REJECT, expired)
        }
        val data = message.data
        val id = data.getLong(CornerInputProtocol.ID)
        if (id <= 0L) return
        if (message.what == CornerInputProtocol.CANCEL) {
            if (id == session.id) invalidate()
            return
        }
        val time = data.getLong(CornerInputProtocol.TIME)
        val x = data.getFloat(CornerInputProtocol.X, Float.NaN)
        val y = data.getFloat(CornerInputProtocol.Y, Float.NaN)
        val metrics = context.resources.displayMetrics
        if (data.getInt(CornerInputProtocol.DISPLAY, -1) != 0 ||
            !session.isSampleValid(time, now, x, y, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())) {
            if (id == session.id) invalidate()
            return
        }
        if (message.what == CornerInputProtocol.PREPARE) {
            if (!session.canPrepare(id, time, now)) return
            invalidate()
            if (!session.prepare(id, time, now)) return
            val allowedSide = prepare(x, y)
            if (allowedSide == null || session.id != id) {
                clear()
                reply(CornerInputProtocol.REJECT, id)
                return
            }
            side = allowedSide
            handler.postDelayed(expiry, CornerInputProtocol.SESSION_TIMEOUT_MS)
            reply(CornerInputProtocol.ACCEPT, id)
            return
        }
        val activeSide = side ?: return
        when (message.what) {
            CornerInputProtocol.ACTIVATE -> if (session.activate(id, time, now)) deliver(message.what, activeSide, x, y)
            CornerInputProtocol.MOVE -> if (session.move(id, time, now)) deliver(message.what, activeSide, x, y)
            CornerInputProtocol.UP -> {
                if (id != session.id) return
                val shouldDeliver = session.up(id, time, now)
                if (session.id == 0L) clear()
                if (shouldDeliver) deliver(message.what, activeSide, x, y)
            }
        }
    }

    fun invalidate() {
        val old = clear()
        if (old != 0L) {
            cancelSafely()
            reply(CornerInputProtocol.REJECT, old)
        }
    }

    fun release() {
        val old = clear()
        if (old != 0L) reply(CornerInputProtocol.REJECT, old)
    }

    private fun clear(): Long {
        handler.removeCallbacks(expiry)
        side = null
        return session.clear()
    }

    private fun reply(what: Int, id: Long) {
        try { client?.send(CornerInputProtocol.message(what, id)) }
        catch (exception: RemoteException) {
            disconnect()
            log(Log.WARN, "CORNER_INPUT_REPLY_FAILED", exception)
        }
    }

    private fun cancelSafely() {
        try { cancel() }
        catch (exception: RuntimeException) { log(Log.WARN, "CORNER_INPUT_CANCEL_FAILED", exception) }
    }

    private fun disconnect() {
        val peer = client
        val recipient = death
        client = null
        death = null
        if (peer != null && recipient != null) {
            try { peer.binder.unlinkToDeath(recipient, 0) }
            catch (exception: RuntimeException) { log(Log.WARN, "CORNER_INPUT_UNLINK_FAILED", exception) }
        }
        val hadSession = session.id != 0L
        clear()
        if (hadSession) cancelSafely()
    }
}
