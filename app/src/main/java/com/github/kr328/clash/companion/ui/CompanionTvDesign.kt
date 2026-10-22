package com.github.kr328.clash.companion.ui

import android.graphics.Bitmap
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.github.kr328.clash.design.Design
import com.github.kr328.clash.design.R
import com.github.kr328.clash.design.util.layoutInflater
import com.github.kr328.clash.design.util.resolveThemedColor
import com.github.kr328.clash.design.util.root
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * TV "Remote control" screen: a 10-foot layout instead of the phone's preference list. The pairing
 * QR sits large on the left (the phone scans it from the sofa), the device name, the steps on the
 * phone and the controls on the right: "Allow remote control", PIN pairing, the paired
 * controllers with Unpair. Everything is a D-pad stop. Phones use [CompanionDesign].
 */
class CompanionTvDesign(
    context: android.content.Context,
    deviceName: String,
    private val onAgentToggle: (Boolean) -> Unit,
    private val onUnpair: (Paired) -> Unit,
) : Design<CompanionDesign.Request>(context) {
    /** One paired controller row. */
    class Paired(val tokenHash: String, val label: String, val pairedAt: String)

    sealed class QrState {
        object Off : QrState()
        object Starting : QrState()
        object NoNetwork : QrState()
        class Ready(val qr: Bitmap, val address: String) : QrState()
    }

    override val root: View = context.layoutInflater
        .inflate(R.layout.design_companion_tv, context.root, false)

    private val qrCard: MaterialCardView = root.findViewById(R.id.companion_tv_qr_card)
    private val qr: ImageView = root.findViewById(R.id.companion_tv_qr)
    private val placeholder: TextView = root.findViewById(R.id.companion_tv_qr_placeholder)
    private val address: TextView = root.findViewById(R.id.companion_tv_address)
    private val enableRow: View = root.findViewById(R.id.companion_tv_enable_row)
    private val enableSwitch: MaterialSwitch = root.findViewById(R.id.companion_tv_enable_switch)
    private val pairingsEmpty: View = root.findViewById(R.id.companion_tv_pairings_empty)
    private val pairings: LinearLayout = root.findViewById(R.id.companion_tv_pairings)

    /** The QR's edge in pixels, for rendering a bitmap that maps 1:1 onto it. */
    val qrSizePx: Int = context.resources.getDimensionPixelSize(R.dimen.companion_tv_qr_size)

    init {
        root.findViewById<TextView>(R.id.companion_tv_device_name).text = deviceName
        root.findViewById<TextView>(R.id.companion_tv_steps).text = context.getString(
            R.string.companion_tv_steps,
            context.getString(R.string.companion_import_row),
            context.getString(R.string.companion_scan_title),
        )
        enableRow.setOnClickListener {
            enableSwitch.isChecked = !enableSwitch.isChecked
            onAgentToggle(enableSwitch.isChecked)
        }
        root.findViewById<View>(R.id.companion_tv_pin).setOnClickListener {
            requests.trySend(CompanionDesign.Request.ShowPin)
        }
        root.findViewById<View>(R.id.companion_tv_devices).setOnClickListener {
            requests.trySend(CompanionDesign.Request.OpenControl)
        }
        // DOWN from the wide toggle row lands on the first button under it, not whichever one the
        // geometric search finds nearer the row's centre.
        enableRow.nextFocusDownId = R.id.companion_tv_pin
        // The toggle is what the screen is for: the D-pad starts on it.
        enableRow.post { enableRow.requestFocus() }
    }

    fun setAgentEnabled(enabled: Boolean) {
        enableSwitch.isChecked = enabled
    }

    fun setQr(state: QrState) {
        val ready = state as? QrState.Ready
        qr.visibility = if (ready != null) View.VISIBLE else View.GONE
        placeholder.visibility = if (ready != null) View.GONE else View.VISIBLE
        address.visibility = if (ready != null) View.VISIBLE else View.GONE
        // White only behind a code to scan; without one a blank white square glared at the room.
        if (ready != null) {
            qrCard.setCardBackgroundColor(android.graphics.Color.WHITE)
            placeholder.setTextColor(android.graphics.Color.BLACK)
        } else {
            qrCard.setCardBackgroundColor(
                context.resolveThemedColor(com.google.android.material.R.attr.colorSurfaceContainer),
            )
            placeholder.setTextColor(context.resolveThemedColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
        if (ready != null) {
            qr.setImageBitmap(ready.qr)
            address.text = ready.address
            return
        }
        qr.setImageDrawable(null)
        placeholder.setText(
            when (state) {
                QrState.Starting -> R.string.companion_tv_qr_starting
                QrState.NoNetwork -> R.string.companion_no_lan
                else -> R.string.companion_tv_qr_off
            },
        )
    }

    private var shownPairings: List<String>? = null

    /** Rebuilds the rows only when the set changed (the activity polls while the screen is open). */
    fun setPairings(list: List<Paired>) {
        val keys = list.map { it.tokenHash + '\u0000' + it.label }
        if (keys == shownPairings) return
        shownPairings = keys
        val focusedHash = (pairings.findFocus()?.tag as? String)
        pairings.removeAllViews()
        pairingsEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        list.forEach { pairings.addView(pairingRow(it)) }
        // Keep the D-pad on the list when the row it was on goes away (unpaired).
        if (focusedHash != null) {
            val again = (0 until pairings.childCount).map { pairings.getChildAt(it) }
                .mapNotNull { it.findViewWithTag<View>(focusedHash) }
                .firstOrNull()
            (again ?: pairings.getChildAt(0)?.findViewById(R.id.companion_tv_unpair) ?: enableRow)
                .requestFocus()
        }
    }

    private fun pairingRow(item: Paired): View {
        val dp = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (56 * dp).toInt()
            setPadding((16 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
            setBackgroundResource(R.drawable.bg_companion_tv_row)
            clipChildren = false
            clipToPadding = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (8 * dp).toInt() }
        }
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(
            TextView(context).apply {
                text = item.label
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextAppearance(R.style.TextAppearance_App_TitleMedium)
            },
        )
        texts.addView(
            TextView(context).apply {
                text = item.pairedAt
                setTextAppearance(R.style.TextAppearance_App_BodySmall)
                alpha = 0.7f
            },
        )
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val unpair = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = R.id.companion_tv_unpair
            tag = item.tokenHash
            setText(R.string.companion_action_unpair)
            setOnClickListener { onUnpair(item) }
        }
        row.addView(unpair)
        return row
    }
}
