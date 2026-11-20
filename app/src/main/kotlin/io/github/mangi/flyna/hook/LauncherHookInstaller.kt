package io.github.mangi.flyna.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.mangi.flyna.gesture.AdaptiveCornerGestureConfig
import io.github.mangi.flyna.gesture.CornerGestureConfig
import io.github.mangi.flyna.gesture.CornerStreamIdentity
import io.github.mangi.flyna.gesture.CornerStreamTracker
import io.github.mangi.flyna.gesture.CornerVisualGate
import io.github.mangi.flyna.platform.DisplayCornerGeometry
import io.github.mangi.flyna.platform.coloros.ColorOsInputEventDisplay
import io.github.mangi.flyna.platform.coloros.ColorOsLauncherInputTarget
import io.github.mangi.flyna.platform.coloros.ColorOsLauncherWorkspaceTarget
import java.io.File
import java.io.IOException
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 只协调桌面翻页与 Quickstep 的竞争识别；输入抢占由 SystemUI 的菜单入口负责。 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
internal class LauncherHookInstaller(
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val streamLock = Any()
    // 两条输入通道的终止时间不同，不能让 monitor 的 CANCEL 清掉普通 View 的会话。
    private val quickstepStreams = WeakHashMap<Context, VisualStream>()
    private val workspaceStreams = WeakHashMap<View, VisualStream>()
    private val environmentState = ModuleEnvironmentState(configuration) { code, exception ->
        module.log(Log.WARN, TAG, code, exception)
    }
    private val availability = CornerInputAvailabilityObserver(
        report = { code, exception -> module.log(Log.WARN, TAG, code, exception) },
        onRelease = ::releaseUnclaimed,
    )
    private var environmentStarted = false
    @Volatile private var workspaceHooksReady = false
    private var lastQuickstepLogAt = -DEFER_LOG_INTERVAL_MS
    private var lastWorkspaceLogAt = -DEFER_LOG_INTERVAL_MS

    private var lastReleaseFailureAt = -10_000L

    private val installationStarted = AtomicBoolean(false)

    fun install(classLoader: ClassLoader, applicationInfo: ApplicationInfo) {
        if (!installationStarted.compareAndSet(false, true)) return
        installWorkspace(classLoader)
        val apk = File(applicationInfo.sourceDir)
        val cache = File(applicationInfo.dataDir, "code_cache/flyna/launcher-symbols")
        val finished = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "Flyna-LauncherDex").apply { isDaemon = true }
        }
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                executor.shutdownNow()
                module.log(Log.WARN, TAG, "LAUNCHER_DEX_RESOLUTION_TIMEOUT")
            }
        }
        mainHandler.postDelayed(timeout, RESOLUTION_TIMEOUT_MS)
        // 解析前的事件完整交给桌面；安装后只观察新 DOWN，不接管已经开始的流。
        executor.execute {
            try {
                val target = ColorOsLauncherInputTarget.resolve(classLoader, apk, cache) { code, exception ->
                    module.log(if (exception == null) Log.INFO else Log.WARN, TAG, code, exception)
                }
                mainHandler.post {
                    if (finished.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(timeout)
                        installResolved(target)
                    }
                }
            } catch (exception: ReflectiveOperationException) {
                module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_TARGET_UNAVAILABLE", exception)
            } catch (exception: IOException) {
                module.log(Log.WARN, TAG, "LAUNCHER_DEX_IO_FAILED", exception)
            } catch (exception: RuntimeException) {
                module.log(Log.WARN, TAG, "LAUNCHER_DEX_RESOLUTION_FAILED", exception)
            } catch (exception: LinkageError) {
                module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_TARGET_LINKAGE_FAILED", exception)
            } finally {
                mainHandler.post {
                    if (finished.compareAndSet(false, true)) mainHandler.removeCallbacks(timeout)
                }
            }
        }
        // 只有这一项任务；完成后线程退出，不保留后台线程池或重试循环。
        executor.shutdown()
    }

    private fun installResolved(target: ColorOsLauncherInputTarget) {
        val buffered = LauncherBufferedInputRelease(target)
        var observer: XposedInterface.HookHandle? = null
        var installed = false
        try {
            observer = buffered.install(module)
            module
                .hook(target.inputMethod)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flyna.launcher.quickstep_move_gate")
                .intercept { chain ->
                    val event = chain.getArg(0) as? MotionEvent
                        ?: return@intercept chain.proceed()
                    val owner = chain.thisObject as? Context
                        ?: return@intercept chain.proceed()
                    val defer = synchronized(streamLock) {
                        val stream = quickstepStreams.getOrPut(owner) { VisualStream() }
                        val deferMove = observe(stream, owner, event)
                        if (!deferMove) false else {
                            val identity = stream.identity.identity
                            try {
                                identity != null && buffered.release(identity)
                            } catch (exception: ReflectiveOperationException) {
                                stream.gate.releaseUnclaimed()
                                logReleaseFailure(exception)
                                false
                            } catch (exception: RuntimeException) {
                                stream.gate.releaseUnclaimed()
                                logReleaseFailure(exception)
                                false
                            }
                        }
                    }
                    // 先放行宿主缓存的 DOWN，避免候选等待其无移动超时。
                    // 原始 DOWN/UP/CANCEL 始终交付。候选失败后当前真实 MOVE 即可继续，
                    // 回注由宿主缓存管理，模块不构造触摸事件，也不调用桌面的 pilferPointers。
                    if (defer) {
                        logDeferred(workspace = false)
                        null
                    } else {
                        chain.proceed()
                    }
                }
            installed = true
            module.log(Log.INFO, TAG, "LAUNCHER_QUICKSTEP_MOVE_GATE_INSTALLED")
        } catch (exception: IllegalArgumentException) {
            module.log(Log.WARN, TAG, "LAUNCHER_QUICKSTEP_MOVE_GATE_REJECTED", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "LAUNCHER_CORNER_INPUT_TARGET_LINKAGE_FAILED", exception)
        } finally {
            if (!installed) observer?.unhook()
        }
    }

    private fun logReleaseFailure(exception: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastReleaseFailureAt < 10_000L) return
        lastReleaseFailureAt = now
        module.log(Log.WARN, TAG, "LAUNCHER_BUFFERED_INPUT_RELEASE_FAILED", exception)
    }

    private fun installWorkspace(loader: ClassLoader) {
        val handles = mutableListOf<XposedInterface.HookHandle>()
        try {
            val target = ColorOsLauncherWorkspaceTarget.resolve(loader)
            handles += module
                .hook(target.dispatchTouchEvent)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId("flyna.launcher.workspace_touch_observer")
                .intercept { chain ->
                    val owner = chain.thisObject as? View
                    val event = chain.getArg(0) as? MotionEvent
                    if (workspaceHooksReady && owner != null && event != null) {
                        synchronized(streamLock) {
                            val stream = workspaceStreams.getOrPut(owner) { VisualStream() }
                            observe(stream, owner.context, event)
                        }
                    }
                    // 只记录输入，不消费普通 View 的 DOWN、点击、长按或终止事件。
                    chain.proceed()
                }
            target.scrollingStarts.forEachIndexed { index, method ->
                handles += module
                    .hook(method)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("flyna.launcher.workspace_scroll_gate_$index")
                    .intercept { chain ->
                        val owner = chain.thisObject as? View
                        val event = chain.getArg(0) as? MotionEvent
                        val defer = workspaceHooksReady && owner != null && event != null &&
                            synchronized(streamLock) {
                                val stream = workspaceStreams[owner]
                                stream != null && deferMove(stream, event)
                            }
                        if (defer) {
                            logDeferred(workspace = true)
                            null
                        } else {
                            chain.proceed()
                        }
                    }
            }
            workspaceHooksReady = true
            module.log(Log.INFO, TAG, "LAUNCHER_WORKSPACE_SCROLL_GATE_INSTALLED")
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "LAUNCHER_WORKSPACE_TARGET_UNAVAILABLE", exception)
        } catch (exception: IllegalArgumentException) {
            module.log(Log.WARN, TAG, "LAUNCHER_WORKSPACE_TARGET_REJECTED", exception)
        } catch (exception: LinkageError) {
            module.log(Log.WARN, TAG, "LAUNCHER_WORKSPACE_TARGET_LINKAGE_FAILED", exception)
        } finally {
            // 不保留只安装了一半的观察器或翻页 Hook；HookFailedError 仍交给框架传播。
            if (!workspaceHooksReady) handles.forEach { it.unhook() }
        }
    }

    /** 调用者持有 streamLock；状态不持有宿主对象、MotionEvent 或 Chain。 */
    private fun observe(stream: VisualStream, owner: Context, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val fresh = stream.identity.begin(
                deviceId = event.deviceId,
                displayId = ColorOsInputEventDisplay.read(event),
                pointerId = event.getPointerId(0),
                eventTimeMillis = event.eventTime,
                x = event.rawX,
                y = event.rawY,
            )
            if (!fresh) return false
            stream.cancel()
            if (stream.identity.identity?.let(availability::wasReleased) == true) return false
            val config = createEligibleConfig(owner, event) ?: return false
            stream.gate.down(event.rawX, event.rawY, config)
            return false
        }
        if (!stream.identity.matches(event.deviceId, ColorOsInputEventDisplay.read(event))) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> deferMove(stream, event)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                stream.cancel()
                stream.identity.finish()
                false
            }
            else -> false
        }
    }

    private fun deferMove(stream: VisualStream, event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE ||
            !stream.identity.matches(event.deviceId, ColorOsInputEventDisplay.read(event))
        ) return false
        return stream.gate.defersMoves
    }

    private fun createEligibleConfig(
        owner: Context,
        event: MotionEvent,
    ): CornerGestureConfig? {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return null
        val settings = configuration.snapshot
        if (!configuration.isAvailable || !settings.enabled) return null
        if (ColorOsInputEventDisplay.read(event) != Display.DEFAULT_DISPLAY) return null
        if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return null
        if (event.pointerCount != 1 || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return null
        startEnvironment(owner)
        if (!environmentState.isGestureAllowed(refreshKeyguard = true)) return null
        if (!availability.isReady) {
            availability.requestIfNeeded()
            return null
        }

        val metrics = owner.resources.displayMetrics
        val region = DisplayCornerGeometry.read(owner, settings.cornerArcLengthDp, settings.cornerArcWidthDp) ?: return null
        return AdaptiveCornerGestureConfig.create(
            triggerRegion = region,
            touchSlop = ViewConfiguration.get(owner).scaledTouchSlop.toFloat(),
            density = metrics.density,
            leftEnabled = settings.leftCornerEnabled,
            rightEnabled = settings.rightCornerEnabled,
        ).takeIf { config ->
            config.triggerRegion.detectSide(
                x = event.rawX,
                y = event.rawY,
                leftEnabled = config.leftEnabled,
                rightEnabled = config.rightEnabled,
            ) != null
        }
    }

    private fun startEnvironment(owner: Context) {
        if (environmentStarted) return
        environmentStarted = true
        val start = {
            availability.start(owner)
            environmentState.start(owner)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) start() else mainHandler.post { start() }
    }

    private fun releaseUnclaimed(identity: CornerStreamIdentity) = synchronized(streamLock) {
        quickstepStreams.values.forEach {
            if (it.identity.matches(identity)) it.gate.releaseUnclaimed()
        }
        workspaceStreams.values.forEach {
            if (it.identity.matches(identity)) it.gate.releaseUnclaimed()
        }
    }

    private fun logDeferred(workspace: Boolean) = synchronized(streamLock) {
        val now = SystemClock.uptimeMillis()
        val last = if (workspace) lastWorkspaceLogAt else lastQuickstepLogAt
        if (now - last >= DEFER_LOG_INTERVAL_MS) {
            if (workspace) lastWorkspaceLogAt = now else lastQuickstepLogAt = now
            module.log(
                Log.INFO, TAG,
                if (workspace) "LAUNCHER_WORKSPACE_SCROLL_DEFERRED" else "LAUNCHER_QUICKSTEP_MOVE_DEFERRED",
            )
        }
    }

    private class VisualStream {
        val identity = CornerStreamTracker()
        val gate = CornerVisualGate()

        fun cancel() {
            gate.cancel()
        }
    }

    private companion object {
        const val TAG = "Flyna"
        const val RESOLUTION_TIMEOUT_MS = 10_000L
        const val DEFER_LOG_INTERVAL_MS = 2_000L
    }
}
