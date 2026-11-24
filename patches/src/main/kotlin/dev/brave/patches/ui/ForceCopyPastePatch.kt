package dev.brave.patches.ui

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import dev.brave.patches.core.braveCorePatch
import dev.brave.patches.media.ActiveTabSwitchFingerprint
import dev.brave.patches.shared.COMPATIBILITY_BRAVE

@Suppress("unused")
val forceCopyPastePatch = patch(
    name = "Force Copy / Paste",
    description = "Automatically unblocks text selection, copy, cut, paste, and context menus on all web pages."
) {
    compatibleWith(COMPATIBILITY_BRAVE)

    dependsOn(braveCorePatch)

    extendWith("extensions/brave.mpe")

    execute {
        ActiveTabSwitchFingerprint.method.apply {
            addInstructions(
                0,
                "invoke-static {}, Ldev/brave/extension/tools/ForceCopyPasteJs;->ensureRegistered()V"
            )
        }
    }
}
