package com.github.kr328.clash.design.main

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import com.github.kr328.clash.design.databinding.DesignMainBinding
import com.github.kr328.clash.design.store.UiStore
import java.util.UUID

/**
 * The Home announcement card under the connect button: preview line, unread dot (per source
 * subscription) and the full-sheet opener. [message] is the already decoded announcement text.
 */
internal fun bindAnnouncementCard(
    context: Context,
    binding: DesignMainBinding,
    uiStore: UiStore,
    message: String,
    announcementUrl: String?,
    support: String?,
    sourceUuid: UUID?,
    sourceName: String?,
    onOpenUrl: ((String) -> Unit)?,
    onSupport: (() -> Unit)?,
) {
    val hasAnnouncement = message.isNotBlank()

    binding.mainHeaderSummary.visibility = View.GONE
    binding.mainHeaderSummary.setOnClickListener(null)
    binding.mainHeaderSummary.isClickable = false

    binding.mainAnnouncementCard.visibility = if (hasAnnouncement) View.VISIBLE else View.GONE
    // The announcement fills the gap under the connect button, so the routing card moves up.
    binding.mainRoutingCard.updateLayoutParams<ViewGroup.MarginLayoutParams> {
        topMargin = (context.resources.displayMetrics.density * if (hasAnnouncement) 16 else 32).toInt()
    }
    if (hasAnnouncement) {
        binding.mainAnnouncementPreview.text = message.replace('\n', ' ')

        val hash = announcementHash(message, announcementUrl, support, sourceName)
        val unread = sourceUuid != null && uiStore.announcementReadHashFor(sourceUuid) != hash
        binding.mainAnnouncementNewDot.visibility = if (unread) View.VISIBLE else View.GONE

        binding.mainAnnouncementCard.setOnClickListener {
            if (sourceUuid != null) {
                uiStore.setAnnouncementReadHashFor(sourceUuid, hash)
                binding.mainAnnouncementNewDot.visibility = View.GONE
            }
            com.github.kr328.clash.design.dialog.AnnouncementSheet.show(
                context = context,
                text = message,
                url = announcementUrl,
                supportUrl = support,
                sourceName = sourceName,
                onOpenUrl = { target -> onOpenUrl?.invoke(target) },
                onSupport = onSupport.takeIf { support != null || onSupport != null },
            )
        }
    } else {
        binding.mainAnnouncementCard.setOnClickListener(null)
        binding.mainAnnouncementNewDot.visibility = View.GONE
    }
}

private fun announcementHash(
    message: String,
    url: String?,
    supportUrl: String?,
    source: String?,
): String {
    val payload = "$message\u0000${url.orEmpty()}\u0000${supportUrl.orEmpty()}\u0000${source.orEmpty()}"
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(payload.toByteArray(Charsets.UTF_8))
    return buildString(bytes.size * 2) {
        val hex = "0123456789abcdef"
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(hex[v ushr 4]); append(hex[v and 0x0F])
        }
    }
}
