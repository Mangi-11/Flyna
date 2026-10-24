package io.github.mangi.flymefreeform.hook

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import io.github.libxposed.api.XposedModule
import io.github.mangi.flymefreeform.config.ModuleSettingsSnapshot
import io.github.mangi.flymefreeform.gesture.AdaptiveCornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureConfig
import io.github.mangi.flymefreeform.gesture.CornerGestureEngine
import io.github.mangi.flymefreeform.gesture.CornerInputBounds
import io.github.mangi.flymefreeform.gesture.CornerInputHandoff
import io.github.mangi.flymefreeform.gesture.CornerSide
import io.github.mangi.flymefreeform.gesture.CornerStreamIdentity
import io.github.mangi.flymefreeform.gesture.CornerStreamTracker
import io.github.mangi.flymefreeform.gesture.CornerTriggerRegion
import io.github.mangi.flymefreeform.gesture.GestureAction
import io.github.mangi.flymefreeform.gesture.GesturePhase
import io.github.mangi.flymefreeform.platform.DisplayCornerGeometry
import io.github.mangi.flymefreeform.platform.coloros.ColorOsInputEventDisplay
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.ceil
import kotlin.math.floor

/** SystemUI 中的受信任 SPY 热区；只抢占触摸，菜单状态仍由 system_server 持有。 */
@SuppressLint("DiscouragedPrivateApi", "PrivateApi", "RtlHardcoded", "WrongConstant")
internal class SystemUiCornerInputMonitor(
    private val context: Context,
    private val module: XposedModule,
    private val configuration: ProcessConfiguration,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager =
        context.getSystemService(WindowManager::class.java)
            ?: error("WindowManager unavailable")
    private val inputManager =
        context.getSystemService(InputManager::class.java)
            ?: error("InputManager unavailable")
    private val environmentState = ModuleEnvironmentState(configuration) { code, exception ->
        module.log(Log.WARN, TAG, code, exception)
    }
    private val inputFeaturesField: Field =
        WindowManager.LayoutParams::class.java.getField("inputFeatures").apply {
            isAccessible = true
        }
    private val setTrustedOverlayMethod: Method =
        WindowManager.LayoutParams::class.java.getMethod("setTrustedOverlay").apply {
            isAccessible = true
        }
    private val getViewRootImplMethod: Method =
        View::class.java.getDeclaredMethod("getViewRootImpl").apply {
            isAccessible = true
        }
    private val pilferPointersMethod: Method =
        InputManager::class.java.getDeclaredMethod("pilferPointers", IBinder::class.java).apply {
            isAccessible = true
        }
    private val bindings = linkedMapOf<CornerSide, CornerBinding>()

    private var settings = ModuleSettingsSnapshot(enabled = false)
    private var pendingSettings: ModuleSettingsSnapshot? = null
    private var lastPilferFailureAt = -PILFER_FAILURE_LOG_INTERVAL_MS

    private val inputClient: SystemUiCornerInputClient = SystemUiCornerInputClient(
        context, mainHandler,
        log = { priority, code, error -> module.log(priority, TAG, code, error) },
        onDisconnected = {
            bindings.values.forEach { it.view.cancelStream() }
            availabilityPublisher.publish()
        },
        onReady = { availabilityPublisher.publish() },
    )
    private val availabilityPublisher: CornerInputAvailabilityPublisher by lazy {
        CornerInputAvailabilityPublisher(
            context, isReady = ::isOwnerReady,
            report = { code, error -> module.log(Log.WARN, TAG, code, error) },
        )
    }
    private var feedbackHook: SystemUiNavigationFeedbackHook? = null

    fun start() {
        check(ColorOsInputEventDisplay.isAvailable) { "Input event display unavailable" }
        availabilityPublisher.start()
        inputClient.start()
        feedbackHook = SystemUiNavigationFeedbackHook(context, module, configuration) { side ->
            bindings.containsKey(side) && canClaim(side)
        }.also { it.install(context.classLoader) }
        environmentState.start(context)
        environmentState.observe { applySettings(configuration.snapshot) }
        configuration.observe { snapshot ->
            if (Looper.myLooper() == Looper.getMainLooper()) {
                acceptSettings(snapshot)
            } else {
                mainHandler.post { acceptSettings(snapshot) }
            }
        }
    }

    private fun acceptSettings(snapshot: ModuleSettingsSnapshot) {
        ensureMainThread()
        if (environmentState.isGestureAllowed() && bindings.values.any { it.view.streamActive }) {
            pendingSettings = snapshot
            return
        }
        applySettings(snapshot)
    }

    private fun applySettings(snapshot: ModuleSettingsSnapshot) {
        ensureMainThread()
        settings = snapshot
        pendingSettings = null
        val allowed = environmentState.isGestureAllowed(refreshKeyguard = true)
        val region = if (allowed) try {
            DisplayCornerGeometry.read(context, snapshot.cornerArcLengthDp, snapshot.cornerArcWidthDp)
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_GEOMETRY_UNAVAILABLE", exception)
            null
        } else null
        updateSide(CornerSide.Left, allowed && snapshot.leftCornerEnabled, region)
        updateSide(CornerSide.Right, allowed && snapshot.rightCornerEnabled, region)
        availabilityPublisher.publish()
    }

    private fun updateSide(side: CornerSide, shouldExist: Boolean, region: CornerTriggerRegion?) {
        val existing = bindings[side]
        val bounds = region?.band(side)?.bounds
        if (!shouldExist || region == null || bounds == null) {
            if (existing != null) removeBinding(side, existing)
            return
        }

        if (existing == null) {
            addBinding(side, region, bounds)
        } else {
            // 显示几何改变时终止旧坐标系中的流，不能让旧起点驱动新位置上的菜单。
            if (existing.view.triggerRegion != region) existing.view.resetTracking()
            existing.view.triggerRegion = region
            if (existing.bounds == bounds) return
            existing.bounds = bounds
            try {
                windowManager.updateViewLayout(existing.view, createLayoutParams(side, bounds))
            } catch (exception: ReflectiveOperationException) {
                module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_UPDATE_FAILED", exception)
                removeBinding(side, existing)
            } catch (exception: RuntimeException) {
                module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_UPDATE_FAILED", exception)
                removeBinding(side, existing)
            }
        }
    }

    private fun addBinding(side: CornerSide, region: CornerTriggerRegion, bounds: CornerInputBounds) {
        val view =
            CornerGestureView(
                context = context,
                side = side,
                triggerRegion = region,
                canClaim = { canClaim(side) },
                pilfer = ::pilfer,
                client = inputClient,
                releaseUnclaimed = { identity ->
                    feedbackHook?.releaseUnclaimed(identity)
                    availabilityPublisher.releaseCandidate(identity)
                },
                onStreamFinished = ::finishStream,
            )
        try {
            windowManager.addView(view, createLayoutParams(side, bounds))
            bindings[side] = CornerBinding(view, bounds)
        } catch (exception: ReflectiveOperationException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_ADD_FAILED", exception)
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_ADD_FAILED", exception)
        }
    }

    private fun removeBinding(side: CornerSide, binding: CornerBinding) {
        bindings.remove(side)
        binding.view.resetTracking()
        try {
            windowManager.removeViewImmediate(binding.view)
        } catch (_: IllegalArgumentException) {
            return
        } catch (exception: RuntimeException) {
            module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_REMOVE_FAILED", exception)
        }
    }

    private fun isOwnerReady(): Boolean =
        inputClient.isReady && settings.enabled && bindings.isNotEmpty() &&
            (!settings.leftCornerEnabled || bindings.containsKey(CornerSide.Left)) &&
            (!settings.rightCornerEnabled || bindings.containsKey(CornerSide.Right))

    private fun canClaim(side: CornerSide): Boolean =
        isOwnerReady() &&
            when (side) {
                CornerSide.Left -> settings.leftCornerEnabled
                CornerSide.Right -> settings.rightCornerEnabled
            } &&
            environmentState.isGestureAllowed(refreshKeyguard = true)

    private fun pilfer(view: View): Boolean =
        try {
            val viewRoot = getViewRootImplMethod.invoke(view) ?: return false
            val inputToken =
                viewRoot.javaClass.getMethod("getInputToken").invoke(viewRoot) as? IBinder
                    ?: return false
            pilferPointersMethod.invoke(inputManager, inputToken)
            true
        } catch (exception: ReflectiveOperationException) {
            logPilferFailure(exception)
            false
        } catch (exception: RuntimeException) {
            logPilferFailure(exception)
            false
        }

    private fun logPilferFailure(exception: Throwable) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPilferFailureAt < PILFER_FAILURE_LOG_INTERVAL_MS) return
        lastPilferFailureAt = now
        module.log(Log.WARN, TAG, "SYSTEMUI_CORNER_SPY_PILFER_FAILED", exception)
    }

    private fun finishStream() {
        mainHandler.post {
            if (bindings.values.any { it.view.streamActive }) return@post
            pendingSettings?.let(::applySettings)
        }
    }

    private fun createLayoutParams(side: CornerSide, bounds: CornerInputBounds): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            (ceil(bounds.right) - floor(bounds.left)).toInt().coerceAtLeast(1),
            (ceil(bounds.bottom) - floor(bounds.top)).toInt().coerceAtLeast(1),
            CORNER_GESTURE_WINDOW_TYPE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
            PixelFormat.TRANSPARENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = floor(bounds.left).toInt()
            y = floor(bounds.top).toInt()
            title = "$CORNER_INPUT_CHANNEL_TITLE-${side.name}"
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
            setTrustedOverlayMethod.invoke(this)
            inputFeaturesField.setInt(
                this,
                inputFeaturesField.getInt(this) or INPUT_FEATURE_SPY,
            )
        }

    private fun ensureMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Corner input windows must run on the SystemUI main thread"
        }
    }

    private data class CornerBinding(
        val view: CornerGestureView,
        var bounds: CornerInputBounds,
    )

    @SuppressLint("ClickableViewAccessibility")
    private class CornerGestureView(
        context: Context,
        private val side: CornerSide,
        var triggerRegion: CornerTriggerRegion,
        private val canClaim: () -> Boolean,
        private val pilfer: (View) -> Boolean,
        private val client: SystemUiCornerInputClient,
        private val releaseUnclaimed: (CornerStreamIdentity) -> Unit,
        private val onStreamFinished: () -> Unit,
    ) : View(context) {
        private val engine = CornerGestureEngine()
        private val handoff = CornerInputHandoff()
        private val stream = CornerStreamTracker()
        private var config: CornerGestureConfig? = null
        private var pointerId = -1
        private var session = 0L
        private var generation = 0L
        private var tracking = false
        private var latestX = 0f
        private var latestY = 0f
        private var latestTime = 0L

        val streamActive: Boolean get() = tracking

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            setWillNotDraw(true)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (!event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) || ColorOsInputEventDisplay.read(event) != 0) {
                resetTracking()
                onStreamFinished()
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val previousIdentity = stream.identity
                    if (!stream.begin(event.deviceId, ColorOsInputEventDisplay.read(event), event.getPointerId(0),
                            event.eventTime, event.rawX, event.rawY)) return true
                    cancelStream(previousIdentity)
                    client.requestConnection()
                    if (event.pointerCount != 1 || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER || !canClaim()) {
                        stream.identity?.let(releaseUnclaimed)
                        return true
                    }
                    val metrics = context.resources.displayMetrics
                    val candidate = AdaptiveCornerGestureConfig.create(
                        triggerRegion,
                        ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
                        metrics.density, side == CornerSide.Left, side == CornerSide.Right,
                    )
                    pointerId = event.getPointerId(0)
                    engine.down(pointerId, event.rawX, event.rawY, candidate, event.eventTime)
                    if (engine.phase != GesturePhase.Armed) {
                        stream.identity?.let(releaseUnclaimed)
                        return true
                    }
                    config = candidate
                    latestX = event.rawX
                    latestY = event.rawY
                    latestTime = event.eventTime
                    tracking = true
                    // 仅在贴边触发带命中的 DOWN 抢占，不等待 MOVE 或跨进程准备确认。
                    if (!handoff.claimInput { pilfer(this) }) {
                        cancelStream()
                        onStreamFinished()
                        return true
                    }
                    val currentGeneration = ++generation
                    session = client.begin(latestX, latestY) { allowed ->
                        if (!tracking || generation != currentGeneration) return@begin
                        if (!allowed) {
                            cancelStream()
                            onStreamFinished()
                        } else {
                            handoff.acceptPreparation()
                            advanceGesture()
                        }
                    }
                    if (session == 0L) cancelStream()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return true
                    if (!canClaim() || !stream.matches(event.deviceId, ColorOsInputEventDisplay.read(event))) {
                        cancelStream()
                        onStreamFinished()
                        return true
                    }
                    val index = event.findPointerIndex(pointerId)
                    val current = config
                    if (index < 0 || current == null) {
                        cancelStream()
                        onStreamFinished()
                        return true
                    }
                    latestX = event.getRawX(index)
                    latestY = event.getRawY(index)
                    latestTime = event.eventTime
                    when (engine.move(pointerId, event.pointerCount, latestX, latestY, current, latestTime)) {
                        is GestureAction.Activate -> advanceGesture()
                        is GestureAction.Update -> {
                            if (!handoff.isActive) advanceGesture()
                            if (handoff.isActive) client.move(session, latestX, latestY, latestTime)
                        }
                        GestureAction.Cancel -> { cancelStream(); onStreamFinished() }
                        else -> if (engine.phase == GesturePhase.Yielded) { cancelStream(); onStreamFinished() }
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN -> { cancelStream(); onStreamFinished() }
                MotionEvent.ACTION_UP -> {
                    if (tracking && handoff.isActive && canClaim() && event.getPointerId(event.actionIndex) == pointerId) {
                        client.finish(session, event.rawX, event.rawY, event.eventTime)
                        session = 0L
                    }
                    resetTracking()
                    onStreamFinished()
                }
                MotionEvent.ACTION_CANCEL -> { resetTracking(); onStreamFinished() }
            }
            return true
        }

        private fun advanceGesture() {
            if (!tracking || !canClaim()) return
            if (!handoff.activateIfReady(
                    menuReady = engine.isMenuReady,
                    activateMenu = { client.activate(session, latestX, latestY, latestTime) },
                )) {
                cancelStream()
                onStreamFinished()
            }
        }

        fun cancelStream(identity: CornerStreamIdentity? = stream.identity) {
            if (tracking && !handoff.isClaimed) identity?.let(releaseUnclaimed)
            generation++
            val id = session
            session = 0L
            tracking = false
            handoff.reset()
            config = null
            pointerId = -1
            engine.cancel()
            if (id != 0L) client.cancel(id)
        }

        fun resetTracking() {
            cancelStream()
            stream.finish()
        }
    }

    private companion object {
        const val TAG = "FlymeFreeform"
        const val INPUT_FEATURE_SPY = 1 shl 2
        const val CORNER_GESTURE_WINDOW_TYPE = 2024
        const val CORNER_INPUT_CHANNEL_TITLE = "FlymeFreeform-corner-input"
        const val PILFER_FAILURE_LOG_INTERVAL_MS = 10_000L
    }
}
