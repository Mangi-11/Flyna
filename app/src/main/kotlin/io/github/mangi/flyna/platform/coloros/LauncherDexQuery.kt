package io.github.mangi.flyna.platform.coloros

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge

/** 同时定位消费入口和宿主缓存触摸的放行能力，避免候选等待系统无移动超时。 */
internal object LauncherDexQuery {
    const val SERVICE_CLASS = "com.android.quickstep.OplusBaseTouchInteractionService"
    const val INTERCEPT_CLASS = "com.oplus.quickstep.gesture.helper.OplusInputInterceptHelper"
    const val RULE_VERSION = "launcher-input-release-v4-dexkit-2.3.0"

    data class Symbols(
        val inputMethod: String,
        val interceptMethod: String,
        val releaseMethod: String,
    )

    fun find(bridge: DexKitBridge): Symbols {
        val service = bridge.getClassData(SERVICE_CLASS)
            ?: throw NoSuchMethodException("LAUNCHER_DEX_SERVICE_MISSING")
        val input = service.methods.findMethod {
            matcher {
                paramTypes("android.view.MotionEvent")
                returnType = "void"
                usingEqStrings("onDispatchInputEvent: ", "TouchInteractionService.onInputEvent")
                addInvoke("Landroid/view/MotionEvent;->getActionMasked()I")
            }
        }.filter { !Modifier.isStatic(it.modifiers) }
            .singleOrNull() ?: throw NoSuchMethodException("LAUNCHER_DEX_INPUT_NOT_UNIQUE")

        val interceptor = bridge.getClassData(INTERCEPT_CLASS)
            ?: throw NoSuchMethodException("LAUNCHER_INPUT_INTERCEPT_CLASS_MISSING")
        val intercept = interceptor.methods.findMethod {
            matcher {
                paramTypes("android.view.MotionEvent", "boolean", "java.lang.Float", "boolean", "boolean", null, "java.lang.String")
                returnType = "boolean"
                usingEqStrings("interceptAndInjectMotionEvent, gestureState is SWIPE_FAIL")
            }
        }.filter { !Modifier.isStatic(it.modifiers) }.singleOrNull()
            ?: throw NoSuchMethodException("LAUNCHER_INPUT_INTERCEPT_NOT_UNIQUE")
        val release = interceptor.methods.findMethod {
            matcher {
                paramTypes("boolean")
                returnType = "void"
                usingEqStrings("injectAllEvent: down no need inject, not in InterceptRegion")
            }
        }.filter { !Modifier.isStatic(it.modifiers) }.singleOrNull()
            ?: throw NoSuchMethodException("LAUNCHER_INPUT_RELEASE_NOT_UNIQUE")
        return Symbols(input.name, intercept.name, release.name)
    }
}
