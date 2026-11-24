package io.github.mangi.flyna.platform.coloros

import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.luckypray.dexkit.DexKitBridge

/** 可选的本地 APK 回归测试，不将厂商二进制或混淆名写入测试仓库。 */
class LauncherDexQueryTest {
    @Test fun resolvesInspectedHostApkUniquely() {
        val apk = System.getenv("FLYNA_TEST_LAUNCHER_APK")
        val nativeLibrary = System.getenv("FLYNA_TEST_DEXKIT_LIBRARY")
        val expected = System.getenv("FLYNA_TEST_LAUNCHER_SYMBOLS")
        assumeNotNull(apk, nativeLibrary, expected)
        System.load(nativeLibrary!!)
        val symbols = DexKitBridge.create(apk!!).use { bridge ->
            bridge.setThreadNum(1)
            LauncherDexQuery.find(bridge)
        }
        val actual = listOf(
            symbols.inputOwner, symbols.inputMethod,
            symbols.interceptOwner, symbols.interceptMethod, symbols.releaseMethod,
        )
        assertEquals(expected, actual.joinToString(","))
    }

    @Test fun workspaceUsesTheMotionEventArgumentOnBothHostGenerations() {
        val legacy = resolveWorkspace(LegacyWorkspace::class.java)
        assertEquals(listOf(0, 0), legacy.scrollingStarts.map { it.eventIndex })
        val current = resolveWorkspace(CurrentWorkspace::class.java)
        assertEquals(listOf(0, 1), current.scrollingStarts.map { it.eventIndex })
    }

    private fun resolveWorkspace(workspace: Class<out View>) =
        ColorOsLauncherWorkspaceTarget.resolve(object : ClassLoader(workspace.classLoader) {
            override fun loadClass(name: String): Class<*> =
                if (name == ColorOsLauncherWorkspaceTarget.WORKSPACE_CLASS) workspace else super.loadClass(name)
        })

    @Suppress("unused", "UNUSED_PARAMETER")
    private class LegacyWorkspace : View(null) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
        fun determineScrollingStart(event: MotionEvent, threshold: Float) = Unit
        fun determineScrollingStart(event: MotionEvent, threshold: Float, flag: Boolean) = Unit
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    private class CurrentWorkspace : View(null) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
        fun determineScrollingStart(event: MotionEvent) = Unit
        fun determineScrollingStart(threshold: Float, event: MotionEvent) = Unit
    }
}
