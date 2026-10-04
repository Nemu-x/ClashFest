package com.github.kr328.clash.design.preference

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.github.kr328.clash.design.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.reflect.KMutableProperty0

interface SelectableListPreference<T> : ClickablePreference {
    var selected: Int

    var listener: OnChangedListener?
}

fun <T> PreferenceScreen.selectableList(
    value: KMutableProperty0<T>,
    values: Array<T>,
    valuesText: Array<Int>,
    @StringRes title: Int,
    @DrawableRes icon: Int? = null,
    configure: SelectableListPreference<T>.() -> Unit = {},
): SelectableListPreference<T> {
    require(values.isNotEmpty() && values.size == valuesText.size)
    val impl = object : SelectableListPreference<T>, ClickablePreference by clickable(title, icon) {
        override var selected: Int = 0
            set(value) {
                field = value

                this.summary = context.getText(valuesText[value])
            }
        override var listener: OnChangedListener? = null
    }

    impl.configure()
    var openDialog: AlertDialog? = null

    launch(Dispatchers.Main) {
        val initial = withContext(Dispatchers.IO) {
            value.get()
        }

        impl.selected = values.indexOf(initial).coerceAtLeast(0)

        impl.clicked {
            if (!isActive || openDialog?.isShowing == true) return@clicked
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(impl.title)
                .setSingleChoiceItems(valuesText.map { context.getText(it) }.toTypedArray(), impl.selected) { choiceDialog, position ->
                    choiceDialog.dismiss()
                    if (position == impl.selected) return@setSingleChoiceItems
                    launch(Dispatchers.Main) {
                        withContext(Dispatchers.IO) { value.set(values[position]) }
                        impl.selected = position
                        impl.listener?.onChanged()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
            openDialog = dialog
            val lifetime = launch(Dispatchers.Main, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    dialog.dismiss()
                }
            }
            dialog.setOnDismissListener {
                if (openDialog === dialog) openDialog = null
                lifetime.cancel()
            }
            dialog.show()
        }
    }

    return impl
}
