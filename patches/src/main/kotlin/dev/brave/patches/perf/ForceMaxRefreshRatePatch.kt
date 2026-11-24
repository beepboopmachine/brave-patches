package dev.brave.patches.perf

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import dev.brave.patches.shared.COMPATIBILITY_BRAVE

private const val DISPLAY_HELPER = "Ldev/brave/extension/perf/DisplayHelper;"

internal object WindowAndroidRefreshRateFingerprint : Fingerprint(
    definingClass = "Lorg/chromium/ui/base/WindowAndroid;",
    name = "r",
    returnType = "V",
    parameters = listOf("F"),
    filters = listOf(
        string("cr_WindowAndroid")
    )
)

@Suppress("unused")
val forceMaxRefreshRatePatch = patch(
    name = "Force Max Refresh Rate",
    description = "Forces display mode to maximum available refresh rate (90Hz / 120Hz / 144Hz) matching physical panel resolution."
) {
    compatibleWith(COMPATIBILITY_BRAVE)

    extendWith("extensions/brave.mpe")

    execute {
        // Insert cleanly at entry (index 0) with zero internal branch labels and zero parameter clobbering
        WindowAndroidRefreshRateFingerprint.method.apply {
            addInstructions(
                0,
                """
                    iget-object v0, p0, Lorg/chromium/ui/base/WindowAndroid;->G:Ljava/util/ArrayList;
                    invoke-virtual {p0}, Lorg/chromium/ui/base/WindowAndroid;->A()Landroid/view/Window;
                    move-result-object v1
                    invoke-static {v1, v0}, $DISPLAY_HELPER->forceMaxRefreshRateForWindow(Landroid/view/Window;Ljava/util/List;)V
                    return-void
                """.trimIndent()
            )
        }
    }
}
