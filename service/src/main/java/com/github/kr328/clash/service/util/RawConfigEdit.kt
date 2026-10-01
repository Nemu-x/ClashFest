package com.github.kr328.clash.service.util

import java.io.File

/**
 * A File-type profile's `config.yaml` can be edited by hand (Properties → Browse
 * files → external editor, through the pending copy). The text the user opens
 * is the *composed* config — subscription base plus everything the in-app
 * editors put into `user_layer.json` — so after a hand edit the file already
 * contains the user's intent in full. Replaying the layer on top at the next
 * VPN start would overwrite whatever the user changed inside a layer-owned
 * block (dns / hosts / tunnels / rules / providers / chain), which is the
 * "my edit reverted after restart" report.
 *
 * So a hand edit takes over: the edited text becomes the base and the layer's
 * content sections are dropped (they are already in the text). Only the config
 * script survives — it is user code, not content, and dropping it silently
 * would lose work.
 *
 * Pure helpers; the processor decides when to call them.
 */
object RawConfigEdit {
    /**
     * True when [candidate] exists and differs byte-for-byte from [imported].
     * The pending copy is made with copyRecursively, so timestamps are useless
     * as a signal; bytes are the truth. A missing [imported] (first import)
     * is not an edit.
     */
    fun isEdited(imported: File, candidate: File): Boolean {
        if (!imported.isFile || !candidate.isFile) return false
        if (imported.length() != candidate.length()) return true
        return !imported.readBytes().contentEquals(candidate.readBytes())
    }

    /** The layer to keep after a hand edit: content sections gone, script kept. */
    fun layerAfterRawEdit(layer: UserLayer): UserLayer = UserLayer(script = layer.script)

    /** True when [layer] carries content the in-app editors own (script excluded). */
    fun hasContentEdits(layer: UserLayer): Boolean = !layer.copy(script = null).isEmpty()
}
