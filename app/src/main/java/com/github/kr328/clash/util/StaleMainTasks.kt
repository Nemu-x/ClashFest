package com.github.kr328.clash.util

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import androidx.core.content.getSystemService
import com.github.kr328.clash.common.constants.Components
import com.github.kr328.clash.common.log.Log

/**
 * MainActivity is singleTask with an empty taskAffinity (task-hijacking hardening). An empty
 * affinity leaves the system nothing to match a dead task on: once the process is killed its
 * task stays in Recents, and the next start through another entry (launcher alias vs the
 * notification, tile, widget or deep link, which name MainActivity itself) opens a new task
 * beside it, two ClashFest windows in Recents. singleTask allows one live instance, so any other
 * task rooted at the main screen is such a leftover.
 */
fun Activity.removeStaleMainTasks() {
    val manager = getSystemService<ActivityManager>() ?: return
    val main = setOf(Components.MAIN_ACTIVITY.className, Components.MAIN_ACTIVITY_ALIAS.className)
    val tasks = runCatching { manager.appTasks }.getOrNull() ?: return
    tasks.forEach { task ->
        runCatching {
            val info = task.taskInfo
            @Suppress("DEPRECATION")
            val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.taskId else info.persistentId
            if (id != taskId && info.baseIntent.component?.className in main) {
                task.finishAndRemoveTask()
                Log.d("Removed stale main task $id")
            }
        }
    }
}
