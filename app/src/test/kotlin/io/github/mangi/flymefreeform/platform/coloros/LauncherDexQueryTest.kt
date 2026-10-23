package io.github.mangi.flymefreeform.platform.coloros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.luckypray.dexkit.DexKitBridge

/** 可选的本地 APK 回归测试，不将厂商二进制或混淆名写入测试仓库。 */
class LauncherDexQueryTest {
    @Test fun resolvesInspectedHostApkUniquely() {
        val apk = System.getenv("FLYMEFREEFORM_TEST_LAUNCHER_APK")
        val nativeLibrary = System.getenv("FLYMEFREEFORM_TEST_DEXKIT_LIBRARY")
        val expected = System.getenv("FLYMEFREEFORM_TEST_LAUNCHER_SYMBOLS")
        assumeNotNull(apk, nativeLibrary, expected)
        System.load(nativeLibrary!!)
        val symbols = DexKitBridge.create(apk!!).use { bridge ->
            bridge.setThreadNum(1)
            val workspace = bridge.getClassData(ColorOsLauncherWorkspaceTarget.WORKSPACE_CLASS)
                ?: error("LAUNCHER_WORKSPACE_CLASS_MISSING")
            val methods = workspace.methods
            val expectedDescriptors = setOf(
                "Lcom/android/launcher3/OplusWorkspace;->dispatchTouchEvent(Landroid/view/MotionEvent;)Z",
                "Lcom/android/launcher3/OplusWorkspace;->determineScrollingStart(Landroid/view/MotionEvent;F)V",
                "Lcom/android/launcher3/OplusWorkspace;->determineScrollingStart(Landroid/view/MotionEvent;FZ)V",
            )
            assertTrue(methods.map { it.descriptor }.containsAll(expectedDescriptors))
            LauncherDexQuery.find(bridge)
        }
        assertEquals(expected, listOf(symbols.inputMethod, symbols.interceptMethod, symbols.releaseMethod).joinToString(","))
    }
}
