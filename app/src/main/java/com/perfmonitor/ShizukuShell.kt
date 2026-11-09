package com.perfmonitor

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * Runs shell commands through Shizuku, which gives this app the same privileges as the
 * adb shell. Used only to read performance data (dumpsys) for other apps.
 */
object ShizukuShell {

    private val newProcessMethod by lazy {
        Shizuku::class.java
            .getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            .apply { isAccessible = true }
    }

    fun isReady(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** Returns the stdout of the command, or null if it failed. */
    fun run(command: String): String? = try {
        val process = newProcessMethod.invoke(
            null,
            arrayOf("sh", "-c", command),
            null,
            null
        ) as Process
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor(3, TimeUnit.SECONDS)
        output
    } catch (e: Throwable) {
        null
    }

    /** Package name of the app currently in the foreground, or null. */
    fun foregroundPackage(ownPackage: String): String? {
        val line = run("dumpsys activity activities | grep -m1 mResumedActivity") ?: return null
        val match = Regex("""\bu\d+\s+([A-Za-z][\w.]*)/""").find(line) ?: return null
        val pkg = match.groupValues[1]
        return if (pkg == ownPackage || pkg == "com.android.systemui") null else pkg
    }

    /** Cumulative count of frames the system has rendered for [pkg], or null. */
    fun totalFrames(pkg: String): Long? {
        val output = run("dumpsys gfxinfo $pkg | grep -m1 'Total frames rendered'") ?: return null
        return Regex("""(\d+)""").find(output)?.groupValues?.get(1)?.toLongOrNull()
    }
}
