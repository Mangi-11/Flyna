package io.github.mangi.flymefreeform.platform.coloros

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import java.io.File
import java.io.IOException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge

internal data class ColorOsLauncherInputTarget(
    val inputMethod: Method,
    val interceptMethod: Method,
    val releaseMethod: Method,
) {
    companion object {
        /** 仅在桌面后台初始化期间调用。缓存命中不加载 native 库，失败后只重新解析一次。 */
        fun resolve(
            loader: ClassLoader,
            apk: File,
            cache: File,
            report: (String, Throwable?) -> Unit,
        ): ColorOsLauncherInputTarget {
            val identity = LauncherSymbolCache.identity(apk)
            val cached = try {
                LauncherSymbolCache.read(cache, identity)
            } catch (exception: IOException) {
                report("LAUNCHER_DEX_CACHE_READ_FAILED", exception)
                null
            }
            if (cached != null) {
                try {
                    return bind(loader, cached).also { report("LAUNCHER_DEX_CACHE_HIT", null) }
                } catch (exception: ReflectiveOperationException) {
                    report("LAUNCHER_DEX_CACHE_REJECTED", exception)
                } catch (exception: IllegalArgumentException) {
                    report("LAUNCHER_DEX_CACHE_REJECTED", exception)
                }
            }
            System.loadLibrary("dexkit")
            val symbols = DexKitBridge.create(apk.path).use { bridge ->
                bridge.setThreadNum(1)
                LauncherDexQuery.find(bridge)
            }
            val target = bind(loader, symbols)
            try {
                LauncherSymbolCache.write(cache, identity, symbols)
            } catch (exception: IOException) {
                report("LAUNCHER_DEX_CACHE_WRITE_FAILED", exception)
            }
            report("LAUNCHER_DEX_RESOLVED", null)
            return target
        }

        // 只定位输入分发，不取得桌面的 InputMonitor 或抢占入口。
        @SuppressLint("PrivateApi")
        private fun bind(loader: ClassLoader, symbols: LauncherDexQuery.Symbols): ColorOsLauncherInputTarget {
            val service = loader.loadClass(LauncherDexQuery.SERVICE_CLASS)
            require(Context::class.java.isAssignableFrom(service))
            val input = service.getDeclaredMethod(symbols.inputMethod, MotionEvent::class.java)
            require(input.returnType == Void.TYPE && !Modifier.isStatic(input.modifiers))
            input.isAccessible = true
            val helper = loader.loadClass(LauncherDexQuery.INTERCEPT_CLASS)
            val intercept = helper.declaredMethods.singleOrNull { method ->
                val types = method.parameterTypes
                method.name == symbols.interceptMethod && method.returnType == Boolean::class.javaPrimitiveType &&
                    !Modifier.isStatic(method.modifiers) && types.size == 7 &&
                    types[0] == MotionEvent::class.java && types[1] == Boolean::class.javaPrimitiveType &&
                    types[2] == java.lang.Float::class.java && types[3] == Boolean::class.javaPrimitiveType &&
                    types[4] == Boolean::class.javaPrimitiveType && !types[5].isPrimitive && types[6] == String::class.java
            } ?: throw NoSuchMethodException("Input interception signature mismatch")
            val release = helper.getDeclaredMethod(symbols.releaseMethod, Boolean::class.javaPrimitiveType)
            require(release.returnType == Void.TYPE && !Modifier.isStatic(release.modifiers))
            intercept.isAccessible = true
            release.isAccessible = true
            return ColorOsLauncherInputTarget(input, intercept, release)
        }
    }
}
