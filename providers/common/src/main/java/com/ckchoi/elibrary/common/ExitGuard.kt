package com.ckchoi.elibrary.common

import android.app.Activity
import android.os.SystemClock
import android.widget.Toast
import java.util.WeakHashMap

object ExitGuard {
    private const val EXIT_WINDOW_MS = 2_000L
    private const val MESSAGE = "종료하시려면 뒤로가기를 다시 실행해주세요"
    private val lastBack = WeakHashMap<Activity, Long>()

    @Synchronized
    fun backOrExit(activity: Activity) {
        // When another Activity exists below this one, normal Android back navigation wins.
        if (!activity.isTaskRoot) {
            lastBack.remove(activity)
            activity.finish()
            return
        }

        val now = SystemClock.elapsedRealtime()
        val previous = lastBack[activity] ?: 0L
        if (now - previous <= EXIT_WINDOW_MS) {
            lastBack.remove(activity)
            activity.finishAndRemoveTask()
            return
        }

        lastBack[activity] = now
        Toast.makeText(activity, MESSAGE, Toast.LENGTH_SHORT).show()
    }

    @Synchronized
    fun reset(activity: Activity) {
        lastBack.remove(activity)
    }
}
