package com.navagent.app

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志。
 *
 * 为什么不用 logcat：这台 vivo X20 Plus A（Android 8.1）的 ROM 会丢弃第三方应用的
 * Log.i / Log.w 输出，logcat 里只剩 SDK 自己的 System.err，排查时完全瞎。
 * 所以关键路径统一写进 /sdcard/Android/data/com.navagent/files/nav.log，
 * 用 `adb pull` 直接取，不依赖 logd。
 *
 * 只用于开发期定位问题，release 时应关掉。
 */
object Dbg {

    private const val FILE_NAME = "nav.log"
    private const val MAX_BYTES = 256 * 1024L

    @Volatile
    private var file: File? = null

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 设置页可以关掉。关掉后 [log] 直接 return，不碰磁盘。 */
    @Volatile
    var enabled: Boolean = true

    fun init(context: Context) {
        val dir = context.getExternalFilesDir(null)
            ?: File(Environment.getExternalStorageDirectory(), "Android/data/${context.packageName}/files")
        dir.mkdirs()
        val f = File(dir, FILE_NAME)
        if (f.exists() && f.length() > MAX_BYTES) f.delete()
        file = f
        enabled = Prefs.fileLog
        log("==== 启动 v${BuildConfig.VERSION_NAME} ====")
    }

    /** 当前日志文件的绝对路径，设置页展示用 */
    fun logPath(): String? = file?.absolutePath

    fun log(msg: String) {
        if (!enabled) return
        val f = file ?: return
        runCatching {
            f.appendText("${fmt.format(Date())} ${Thread.currentThread().name} $msg\n")
        }
    }
}
