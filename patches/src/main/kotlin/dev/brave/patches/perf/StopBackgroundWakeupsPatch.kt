package dev.brave.patches.perf

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import dev.brave.patches.shared.COMPATIBILITY_BRAVE

internal object BackgroundSyncScheduleFingerprint : Fingerprint(
    definingClass = "Lorg/chromium/chrome/browser/background_sync/BackgroundSyncBackgroundTaskScheduler;",
    name = "scheduleOneOffTask",
    returnType = "Z",
    parameters = listOf("I", "J")
)

@Suppress("unused")
val stopBackgroundWakeupsPatch = patch(
    name = "Stop Background Wakeups",
    description = "Disables Chromium's background-sync task scheduler gate to prevent periodic background wakeups."
) {
    compatibleWith(COMPATIBILITY_BRAVE)

    execute {
        BackgroundSyncScheduleFingerprint.method.apply {
            addInstructions(
                0,
                """
                    const/4 v0, 0x0
                    return v0
                """.trimIndent()
            )
        }
    }
}
