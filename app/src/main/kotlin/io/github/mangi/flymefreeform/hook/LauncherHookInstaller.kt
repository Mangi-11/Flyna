package io.github.mangi.flymefreeform.hook

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
import io.github.mangi.flymefreeform.gesture.AdaptiveCornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerStreamIdentity
import io.github.mangi.flymefreeform.gesture.CornerStreamTracker
import io.github.mangi.flymefreeform.gesture.CornerTriggerRegion
import io.github.mangi.flymefreeform.gesture.CornerVisualGate
import io.github.mangi.flymefreeform.platform.coloros.ColorOsInputEventDisplay
import io.github.mangi.flymefreeform.platform.coloros.ColorOsLauncherInputTarget
import io.github.mangi.flymefreeform.platform.coloros.ColorOsLauncherWorkspaceTarget
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
        val cache = File(applicationInfo.dataDir, "code_cache/flymefreeform/launcher-symbols")
        val finished = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "FlymeFreeform-LauncherDex").apply { isDaemon = true }
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
                .setId("flymefreeform.launcher.quickstep_move_gate")
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
                .setId("flymefreeform.launcher.workspace_touch_observer")
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
                    .setId("flymefreeform.launcher.workspace_scroll_gate_$index")
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
            stream.pointerId = event.getPointerId(0)
            stream.gate.down(stream.pointerId, event.rawX, event.rawY, config, event.eventTime)
            return false
        }
        if (!stream.identity.matches(event.deviceId, ColorOsInputEventDisplay.read(event))) return false
        return when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> deferMove(stream, event)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                stream.gate.pointerDown()
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
            !stream.identity.matches(event.deviceId, ColorOsInputEventDisplay.read(event)) || stream.pointerId < 0
        ) return false
        if (!environmentState.isGestureAllowed() || !availability.isReady) {
            stream.gate.cancelCandidate()
        }
        // 原指针抬起后仍传入真实剩余指针：候选因身份不符退出，已识别流保持到终止。
        val pointerIndex = event.findPointerIndex(stream.pointerId).takeIf { it >= 0 } ?: 0
        return stream.gate.move(
            event.getPointerId(pointerIndex), event.pointerCount,
            event.getRawX(pointerIndex), event.getRawY(pointerIndex), event.eventTime,
        )
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
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        if (width <= 0f || height <= 0f) return null
        return AdaptiveCornerGestureConfig.create(
            displayWidth = width,
            displayHeight = height,
            touchSlop = ViewConfiguration.get(owner).scaledTouchSlop.toFloat(),
            density = metrics.density,
            triggerRangeDp = settings.cornerTriggerRangeDp,
            leftEnabled = settings.leftCornerEnabled,
            rightEnabled = settings.rightCornerEnabled,
        ).takeIf { config ->
            CornerTriggerRegion.detectSide(
                x = event.rawX,
                y = event.rawY,
                displayWidth = config.displayWidth,
                displayHeight = config.displayHeight,
                radius = config.triggerRadius,
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
            environmentState.observe { cancelCandidates() }
            configuration.observe { cancelCandidates() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) start() else mainHandler.post { start() }
    }

    private fun cancelCandidates() = synchronized(streamLock) {
        quickstepStreams.values.forEach { it.gate.cancelCandidate() }
        workspaceStreams.values.forEach { it.gate.cancelCandidate() }
        // 身份与已识别流的视觉门保持到终止，避免中途重新识别或恢复竞争动画。
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
        var pointerId = -1

        fun cancel() {
            gate.cancel()
            pointerId = -1
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val RESOLUTION_TIMEOUT_MS = 10_000L
        const val DEFER_LOG_INTERVAL_MS = 2_000L
    }
}
