package com.hwcamtrack

import android.app.Application
import android.content.Context
import android.util.Log

/**
 * 应用入口。
 *
 * 除了作为全局初始化落点，它还负责一件很实际的事：**把崩溃堆栈留下来**。
 *
 * 这类 App 的故障现场在用户手机上（记录仪热点、各种奇怪的摄像头固件），
 * 让用户去导 logcat 基本不现实。这里把未捕获异常的堆栈存进 SharedPreferences，
 * 下次启动时由 MainActivity 直接打进 App 自己的日志面板——
 * "用着用着就闪退"就变成屏幕上可读、可复制的一段文字。
 *
 * 崩溃处理后仍把异常交回系统默认处理，App 的崩溃行为保持不变。
 */
class TrackApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val detail = buildString {
                    append("Thread ").append(thread.name).append(" crashed:\n")
                    append(Log.getStackTraceString(error))
                }
                applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, detail)
                    .apply()
            }
            // 交回系统默认处理，让崩溃行为保持正常（弹系统对话框 / 杀进程）
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private const val PREFS = "hwcamtrack_crash"
        private const val KEY_LAST_CRASH = "last_crash"

        /** 取出并清除上次的崩溃记录（只提示一次）。 */
        fun consumeLastCrash(context: Context): String? {
            val prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val text = prefs.getString(KEY_LAST_CRASH, null) ?: return null
            prefs.edit().remove(KEY_LAST_CRASH).apply()
            return text
        }
    }
}
