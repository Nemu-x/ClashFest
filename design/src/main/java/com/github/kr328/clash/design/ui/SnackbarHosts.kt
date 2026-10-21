package com.github.kr328.clash.design.ui

import android.app.Activity
import android.content.Context
import android.view.View
import com.github.kr328.clash.design.util.hostActivity
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Dialog windows need their own Snackbar host, above the activity's content. */
internal object SnackbarHosts {
    private val hosts = WeakHashMap<Activity, MutableList<WeakReference<View>>>()

    fun register(context: Context, view: View) {
        val activity = context.hostActivity() ?: return
        hosts.getOrPut(activity) { mutableListOf() }.add(WeakReference(view))
    }

    fun unregister(context: Context, view: View) {
        val activity = context.hostActivity() ?: return
        val stack = hosts[activity] ?: return
        stack.removeAll { it.get() == null || it.get() === view }
        if (stack.isEmpty()) hosts.remove(activity)
    }

    fun resolve(context: Context, fallback: View): View {
        val stack = context.hostActivity()?.let(hosts::get) ?: return fallback
        stack.removeAll { it.get() == null }
        return stack.asReversed().firstNotNullOfOrNull { it.get()?.takeIf { view -> view.isAttachedToWindow && view.isShown } } ?: fallback
    }
}
