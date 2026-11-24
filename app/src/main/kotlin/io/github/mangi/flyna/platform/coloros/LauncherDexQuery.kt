package io.github.mangi.flyna.platform.coloros

import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge

/** 同时定位消费入口和宿主缓存触摸的放行能力，避免候选等待系统无移动超时。 */
internal object LauncherDexQuery {
    const val SERVICE_CLASS = "com.android.quickstep.TouchInteractionService"
    const val RULE_VERSION = "launcher-input-release-v5-dexkit-2.3.0"

    data class Symbols(
        val inputOwner: String,
        val inputMethod: String,
        val interceptOwner: String,
        val interceptMethod: String,
        val releaseMethod: String,
    )

    fun find(bridge: DexKitBridge): Symbols {
        val service = bridge.getClassData(SERVICE_CLASS)
            ?: throw NoSuchMethodException("LAUNCHER_DEX_SERVICE_MISSING")
        val hierarchy = generateSequence(service) { it.superClass }.toList()
        val input = bridge.findMethod {
            searchInClass(hierarchy)
            matcher {
                paramTypes("android.view.MotionEvent")
                returnType = "void"
                usingEqStrings("onDispatchInputEvent: ", "TouchInteractionService.onInputEvent")
                addInvoke("Landroid/view/MotionEvent;->getActionMasked()I")
            }
        }.filter { !Modifier.isStatic(it.modifiers) }
            .singleOrNull() ?: throw NoSuchMethodException("LAUNCHER_DEX_INPUT_NOT_UNIQUE")

        val intercept = bridge.findMethod {
            searchPackages("com.oplus.quickstep.gesture.helper")
            matcher {
                paramTypes("android.view.MotionEvent", "boolean", "java.lang.Float", "boolean", "boolean", null, "java.lang.String")
                returnType = "boolean"
                usingEqStrings("interceptAndInjectMotionEvent, gestureState is SWIPE_FAIL")
            }
        }.filter { !Modifier.isStatic(it.modifiers) && it.paramTypeNames[5] !in primitiveTypes }.singleOrNull()
            ?: throw NoSuchMethodException("LAUNCHER_INPUT_INTERCEPT_NOT_UNIQUE")
        val interceptor = intercept.declaredClass
            ?: throw NoSuchMethodException("LAUNCHER_INPUT_INTERCEPT_CLASS_MISSING")
        val release = interceptor.methods.findMethod {
            matcher {
                paramTypes("boolean")
                returnType = "void"
                usingEqStrings("injectAllEvent: down no need inject, not in InterceptRegion")
            }
        }.filter { !Modifier.isStatic(it.modifiers) }.singleOrNull()
            ?: throw NoSuchMethodException("LAUNCHER_INPUT_RELEASE_NOT_UNIQUE")
        return Symbols(input.declaredClassName, input.name, interceptor.name, intercept.name, release.name)
    }

    private val primitiveTypes = setOf("boolean", "byte", "char", "short", "int", "long", "float", "double")
}
