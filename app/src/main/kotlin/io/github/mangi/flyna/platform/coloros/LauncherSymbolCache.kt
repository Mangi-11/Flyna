package io.github.mangi.flyna.platform.coloros

import java.io.File
import java.io.IOException

/** 缓存仅包含符号；绑定 APK 内容与查询规则，不跨 APK 更新复用。调用方在后台执行 I/O。 */
internal object LauncherSymbolCache {
    private val memberName = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    private val className = Regex("${memberName.pattern}(\\.${memberName.pattern})*")

    fun identity(apk: File): String = DexApkIdentity.compute(apk, LauncherDexQuery.RULE_VERSION)

    fun read(file: File, identity: String): LauncherDexQuery.Symbols? {
        if (!file.isFile || file.length() > 4096) return null
        val lines = file.readLines()
        if (lines.size != 6 || lines[0] != identity) return null
        if (!className.matches(lines[1]) || !className.matches(lines[3])) return null
        if (!memberName.matches(lines[2]) || !memberName.matches(lines[4]) || !memberName.matches(lines[5])) return null
        return LauncherDexQuery.Symbols(lines[1], lines[2], lines[3], lines[4], lines[5])
    }

    fun write(file: File, identity: String, symbols: LauncherDexQuery.Symbols) {
        val directory = file.parentFile ?: throw IOException("LAUNCHER_CACHE_DIRECTORY_MISSING")
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("LAUNCHER_CACHE_DIRECTORY_FAILED")
        val temporary = File.createTempFile("launcher-", ".tmp", directory)
        try {
            temporary.writeText(
                listOf(
                    identity, symbols.inputOwner, symbols.inputMethod,
                    symbols.interceptOwner, symbols.interceptMethod, symbols.releaseMethod,
                ).joinToString("\n"),
            )
            if (!temporary.renameTo(file)) throw IOException("LAUNCHER_CACHE_RENAME_FAILED")
        } finally {
            if (temporary.exists() && !temporary.delete()) throw IOException("LAUNCHER_CACHE_CLEANUP_FAILED")
        }
    }
}
