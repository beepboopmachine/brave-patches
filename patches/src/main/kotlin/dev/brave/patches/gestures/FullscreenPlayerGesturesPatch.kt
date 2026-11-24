package dev.brave.patches.gestures

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import dev.brave.patches.shared.COMPATIBILITY_BRAVE

internal object FullscreenEnterFingerprint : Fingerprint(
    definingClass = "Lv28;",
    name = "g",
    returnType = "V",
    parameters = listOf(
        "Lorg/chromium/chrome/browser/tab/Tab;",
        "Lorg/chromium/chrome/browser/fullscreen/FullscreenOptions;"
    ),
    filters = listOf(
        string("FullscreenHtmlApiHandler.enterFullScreen")
    )
)

internal object FullscreenRequestFingerprint : Fingerprint(
    definingClass = "Lv28;",
    name = "h",
    returnType = "V",
    parameters = listOf(
        "Lorg/chromium/chrome/browser/tab/Tab;",
        "Lorg/chromium/chrome/browser/fullscreen/FullscreenOptions;"
    )
)

internal object FullscreenExitFingerprint : Fingerprint(
    definingClass = "Lv28;",
    returnType = "V",
    parameters = listOf(),
    filters = listOf(
        string("FullscreenHtmlApiHandler.exitFullScreen")
    )
)

private const val GESTURE_OVERLAY = "Ldev/brave/extension/gestures/VideoGestureOverlay;"

@Suppress("unused")
val fullscreenPlayerGesturesPatch = patch(
    name = "Fullscreen Player Gestures",
    description = "Enables swipe gestures for volume, brightness, and seeking, plus long-press 2x speed in HTML5 fullscreen video."
) {
    compatibleWith(COMPATIBILITY_BRAVE)

    extendWith("extensions/brave.mpe")

    execute {
        // 1a. Hook v28.g(Tab, FullscreenOptions) on committed enterFullScreen path
        FullscreenEnterFingerprint.method.apply {
            val anchorIdx = instructions.indices.firstOrNull { idx ->
                val ins = instructions[idx]
                ins is ReferenceInstruction && (ins.reference as? StringReference)?.string == "FullscreenHtmlApiHandler.enterFullScreen"
            } ?: throw IllegalStateException("Could not find FullscreenHtmlApiHandler.enterFullScreen anchor")

            val iputTabIdx = (anchorIdx until instructions.size).firstOrNull { idx ->
                val ins = instructions[idx]
                ins.opcode == Opcode.IPUT_OBJECT &&
                    ((ins as? ReferenceInstruction)?.reference as? FieldReference)?.type == "Lorg/chromium/chrome/browser/tab/Tab;"
            } ?: (anchorIdx + 4).coerceAtMost(instructions.size - 2)

            addInstructions(
                iputTabIdx + 1,
                "invoke-static/range {p0 .. p1}, $GESTURE_OVERLAY->attach(Ljava/lang/Object;Lorg/chromium/chrome/browser/tab/Tab;)V"
            )
        }

        // 1b. Also hook v28.h(Tab, FullscreenOptions) entry so deferred/async fullscreen transitions also attach
        FullscreenRequestFingerprint.methodOrNull?.apply {
            addInstructions(
                0,
                "invoke-static/range {p0 .. p1}, $GESTURE_OVERLAY->attach(Ljava/lang/Object;Lorg/chromium/chrome/browser/tab/Tab;)V"
            )
        }

        // 2. Hook exitFullScreen at method entry
        FullscreenExitFingerprint.method.apply {
            addInstructions(
                0,
                "invoke-static/range {p0 .. p0}, $GESTURE_OVERLAY->detach(Ljava/lang/Object;)V"
            )
        }
    }
}
