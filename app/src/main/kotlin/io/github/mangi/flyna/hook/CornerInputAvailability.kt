package io.github.mangi.flyna.hook

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import io.github.mangi.flyna.BuildConfig
import io.github.mangi.flyna.gesture.CornerReleaseHistory
import io.github.mangi.flyna.gesture.CornerStreamIdentity
import io.github.mangi.flyna.platform.coloros.CornerInputProtocol

/** 只公布菜单入口是否可接管，不接受手势、任务或配置操作。 */
internal class CornerInputAvailabilityPublisher(
    context: Context,
    private val isReady: () -> Boolean,
    report: (String, Throwable) -> Unit,
) {
    private val context = context.applicationContext ?: context
    private val handler = Handler(Looper.getMainLooper())
    private val errors = AvailabilityErrors(report)
    private val lifetime = Binder()
    private var started = false
    private var launcherUid = -1
    private var lastPublished: Boolean? = null
    private var lastRequestAt = -AvailabilityProtocol.REQUEST_INTERVAL_MS
    private val requestReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!started || intent?.action != AvailabilityProtocol.REQUEST ||
                launcherUid < 0 || sentFromUid != launcherUid
            ) return
            val now = SystemClock.uptimeMillis()
            if (now - lastRequestAt < AvailabilityProtocol.REQUEST_INTERVAL_MS) return
            lastRequestAt = now
            sendState(currentReadiness(), force = true)
        }
    }

    fun start() {
        checkMainThread()
        if (started) return
        try {
            launcherUid = context.packageManager.getPackageUid(AvailabilityProtocol.LAUNCHER, 0)
            context.registerReceiver(
                requestReceiver, IntentFilter(AvailabilityProtocol.REQUEST),
                null, handler, Context.RECEIVER_EXPORTED,
            )
            started = true
            publish()
        } catch (exception: android.content.pm.PackageManager.NameNotFoundException) {
            errors.report("CORNER_AVAILABILITY_LAUNCHER_MISSING", exception)
        } catch (exception: RuntimeException) {
            errors.report("CORNER_AVAILABILITY_PUBLISHER_START_FAILED", exception)
        }
    }

    fun publish() {
        checkMainThread()
        if (started) sendState(currentReadiness(), force = false)
    }

    /** 仅供实际输入所有者确认尚未 pilfer 时调用；本地视觉识别不代表取得输入所有权。 */
    fun releaseCandidate(identity: CornerStreamIdentity) {
        checkMainThread()
        if (!started || !AvailabilityProtocol.validIdentity(identity)) return
        try {
            val intent = Intent(AvailabilityProtocol.RELEASE).setPackage(AvailabilityProtocol.LAUNCHER)
                .putExtra(AvailabilityProtocol.VERSION, AvailabilityProtocol.CURRENT_VERSION)
                .putExtra(AvailabilityProtocol.DEVICE, identity.deviceId)
                .putExtra(AvailabilityProtocol.DISPLAY, identity.displayId)
                .putExtra(AvailabilityProtocol.POINTER, identity.pointerId)
                .putExtra(AvailabilityProtocol.TIME, identity.eventTimeMillis)
                .putExtra(AvailabilityProtocol.X, identity.x)
                .putExtra(AvailabilityProtocol.Y, identity.y)
                .putExtras(Bundle().apply { putBinder(AvailabilityProtocol.TOKEN, lifetime) })
            context.sendBroadcast(intent, null, AvailabilityProtocol.sharedIdentity())
        } catch (exception: RuntimeException) {
            errors.report("CORNER_AVAILABILITY_RELEASE_FAILED", exception)
        }
    }

    fun close() {
        checkMainThread()
        if (!started) return
        sendState(false, force = true)
        started = false
        lastPublished = null
        try { context.unregisterReceiver(requestReceiver) }
        catch (exception: RuntimeException) {
            errors.report("CORNER_AVAILABILITY_PUBLISHER_CLOSE_FAILED", exception)
        }
    }

    private fun currentReadiness(): Boolean = try { isReady() }
    catch (exception: RuntimeException) {
        errors.report("CORNER_AVAILABILITY_STATE_FAILED", exception)
        false
    }

    private fun sendState(ready: Boolean, force: Boolean) {
        if (!force && lastPublished == ready) return
        try {
            val intent = Intent(AvailabilityProtocol.STATE).setPackage(AvailabilityProtocol.LAUNCHER)
                .putExtra(AvailabilityProtocol.VERSION, AvailabilityProtocol.CURRENT_VERSION)
                .putExtra(AvailabilityProtocol.READY, ready)
                .putExtras(Bundle().apply { putBinder(AvailabilityProtocol.TOKEN, lifetime) })
            context.sendBroadcast(intent, null, AvailabilityProtocol.sharedIdentity())
            lastPublished = ready
        } catch (exception: RuntimeException) {
            errors.report("CORNER_AVAILABILITY_PUBLISH_FAILED", exception)
        }
    }
}

/** 桌面进程只读能力缓存；Binder 死亡立即撤销，不在 MOVE 中发 IPC。 */
internal class CornerInputAvailabilityObserver(
    report: (String, Throwable) -> Unit,
    private val onRelease: (CornerStreamIdentity) -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private val errors = AvailabilityErrors(report)
    private var context: Context? = null
    private var registered = false
    private var systemUiUid = -1
    private var lastRequestAt = -AvailabilityProtocol.REQUEST_INTERVAL_MS
    @Volatile private var ready = false
    @Volatile private var lifetime: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private var deathLinked = false
    private val releases = CornerReleaseHistory()

    val isReady: Boolean
        get() = ready && lifetime?.isBinderAlive == true

    /** 同一原始 DOWN 的另一条输入通道可能稍后才到，保留短期身份避免重新布防。 */
    fun wasReleased(identity: CornerStreamIdentity): Boolean = synchronized(releases) {
        releases.contains(identity, SystemClock.uptimeMillis())
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            if (!registered || intent == null ||
                (action != AvailabilityProtocol.STATE && action != AvailabilityProtocol.RELEASE) ||
                systemUiUid < 0 || sentFromUid != systemUiUid
            ) return
            try {
                if (intent.getIntExtra(AvailabilityProtocol.VERSION, -1) != AvailabilityProtocol.CURRENT_VERSION) {
                    if (action == AvailabilityProtocol.STATE) ready = false
                    return
                }
                val token = intent.extras?.getBinder(AvailabilityProtocol.TOKEN)
                if (token == null) {
                    if (action == AvailabilityProtocol.STATE) ready = false
                    return
                }
                if (action == AvailabilityProtocol.RELEASE) {
                    if (lifetime != token || !token.isBinderAlive) return
                    val identity = AvailabilityProtocol.readIdentity(intent) ?: return
                    synchronized(releases) { releases.record(identity, SystemClock.uptimeMillis()) }
                    onRelease(identity)
                    return
                }
                if (lifetime != token && !attach(token)) return
                ready = intent.getBooleanExtra(AvailabilityProtocol.READY, false) && token.isBinderAlive
            } catch (exception: RuntimeException) {
                if (action == AvailabilityProtocol.STATE) ready = false
                errors.report("CORNER_AVAILABILITY_STATE_REJECTED", exception)
            }
        }
    }

    fun start(owner: Context) {
        checkMainThread()
        if (context != null) return
        val appContext = owner.applicationContext ?: owner
        context = appContext
        try {
            systemUiUid = appContext.packageManager.getPackageUid(AvailabilityProtocol.SYSTEM_UI, 0)
            appContext.registerReceiver(
                stateReceiver, IntentFilter(AvailabilityProtocol.STATE).apply {
                    addAction(AvailabilityProtocol.RELEASE)
                },
                CornerInputProtocol.PERMISSION, handler, Context.RECEIVER_EXPORTED,
            )
            registered = true
            requestIfNeeded()
        } catch (exception: android.content.pm.PackageManager.NameNotFoundException) {
            errors.report("CORNER_AVAILABILITY_SYSTEM_UI_MISSING", exception)
        } catch (exception: RuntimeException) {
            errors.report("CORNER_AVAILABILITY_OBSERVER_START_FAILED", exception)
        }
    }

    /** 仅在启动或未就绪的真实 DOWN 调用；没有后台轮询、同步 Binder 等待或无限重试。 */
    fun requestIfNeeded() {
        if (Looper.myLooper() != handler.looper) {
            handler.removeCallbacks(request)
            handler.post(request)
            return
        }
        val owner = context ?: return
        if (!registered || isReady) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRequestAt < AvailabilityProtocol.REQUEST_INTERVAL_MS) return
        lastRequestAt = now
        try {
            owner.sendBroadcast(
                Intent(AvailabilityProtocol.REQUEST).setPackage(AvailabilityProtocol.SYSTEM_UI),
                null, AvailabilityProtocol.sharedIdentity(),
            )
        } catch (exception: RuntimeException) {
            errors.report("CORNER_AVAILABILITY_REQUEST_FAILED", exception)
        }
    }

    fun close() {
        checkMainThread()
        handler.removeCallbacks(request)
        ready = false
        detach()
        if (registered) {
            registered = false
            try { context?.unregisterReceiver(stateReceiver) }
            catch (exception: RuntimeException) {
                errors.report("CORNER_AVAILABILITY_OBSERVER_CLOSE_FAILED", exception)
            }
        }
        context = null
        lastRequestAt = -AvailabilityProtocol.REQUEST_INTERVAL_MS
        synchronized(releases) { releases.clear() }
    }

    private val request = Runnable { requestIfNeeded() }

    private fun attach(token: IBinder): Boolean {
        detach()
        val recipient = IBinder.DeathRecipient {
            if (lifetime == token) ready = false
            handler.post { if (lifetime == token) detach() }
        }
        lifetime = token
        death = recipient
        return try {
            token.linkToDeath(recipient, 0)
            deathLinked = true
            if (!token.isBinderAlive) { detach(); false } else true
        } catch (exception: RemoteException) {
            detach()
            errors.report("CORNER_AVAILABILITY_OWNER_DIED", exception)
            false
        } catch (exception: RuntimeException) {
            detach()
            errors.report("CORNER_AVAILABILITY_DEATH_LINK_FAILED", exception)
            false
        }
    }

    private fun detach() {
        ready = false
        val token = lifetime
        val recipient = death
        val linked = deathLinked
        lifetime = null
        death = null
        deathLinked = false
        if (linked && token != null && recipient != null) {
            try { token.unlinkToDeath(recipient, 0) }
            catch (exception: RuntimeException) {
                errors.report("CORNER_AVAILABILITY_DEATH_CLEANUP_FAILED", exception)
            }
        }
    }
}

private object AvailabilityProtocol {
    const val REQUEST = "${BuildConfig.APPLICATION_ID}.action.REQUEST_CORNER_INPUT_AVAILABILITY"
    const val STATE = "${BuildConfig.APPLICATION_ID}.action.CORNER_INPUT_AVAILABILITY"
    const val RELEASE = "${BuildConfig.APPLICATION_ID}.action.RELEASE_UNCLAIMED_CORNER_INPUT"
    const val LAUNCHER = "com.android.launcher"
    const val SYSTEM_UI = "com.android.systemui"
    const val VERSION = "version"
    const val CURRENT_VERSION = 1
    const val READY = "ready"
    const val TOKEN = "lifetime"
    const val DEVICE = "device"
    const val DISPLAY = "display"
    const val POINTER = "pointer"
    const val TIME = "eventTime"
    const val X = "x"
    const val Y = "y"
    const val REQUEST_INTERVAL_MS = 1_000L
    fun sharedIdentity(): Bundle = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()

    fun readIdentity(intent: Intent): CornerStreamIdentity? {
        val identity = CornerStreamIdentity(
            deviceId = intent.getIntExtra(DEVICE, Int.MIN_VALUE),
            displayId = intent.getIntExtra(DISPLAY, -1),
            pointerId = intent.getIntExtra(POINTER, -1),
            eventTimeMillis = intent.getLongExtra(TIME, -1L),
            x = intent.getFloatExtra(X, Float.NaN),
            y = intent.getFloatExtra(Y, Float.NaN),
        )
        return identity.takeIf(::validIdentity)
    }

    fun validIdentity(identity: CornerStreamIdentity): Boolean {
        val now = SystemClock.uptimeMillis()
        return identity.deviceId >= -1 && identity.displayId == 0 && identity.pointerId in 0..31 &&
            identity.x.isFinite() && identity.y.isFinite() &&
            identity.eventTimeMillis >= 0 && identity.eventTimeMillis <= now &&
            now - identity.eventTimeMillis <= CornerReleaseHistory.TTL_MS
    }
}

private class AvailabilityErrors(private val onFailure: (String, Throwable) -> Unit) {
    private var lastAt = -10_000L
    fun report(code: String, error: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAt < 10_000L) return
        lastAt = now
        onFailure(code, error)
    }
}

private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper())
