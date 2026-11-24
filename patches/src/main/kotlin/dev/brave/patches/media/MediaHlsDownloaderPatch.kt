package dev.brave.patches.media

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import dev.brave.patches.core.braveCorePatch
import dev.brave.patches.shared.COMPATIBILITY_BRAVE

private const val TOOLBAR_HOOK = "Ldev/brave/extension/media/ToolbarHook;"

@Suppress("unused")
val mediaHlsDownloaderPatch = patch(
    name = "Media & HLS Downloader",
    description = "Adds a native toolbar button, HLS stream sniffer, and background MP4 remuxer to Brave Browser."
) {
    compatibleWith(COMPATIBILITY_BRAVE)

    dependsOn(braveCorePatch)

    extendWith("extensions/brave.mpe")

    execute {
        // 1. Android 14+ Security: Ensure required foreground service permissions and service are in AndroidManifest.xml
        resourceXml("AndroidManifest.xml").use { xml ->
            val existingPerms = xml.elements("uses-permission")
                .mapNotNull { it.attribute("android:name") }
                .toSet()

            if ("android.permission.FOREGROUND_SERVICE" !in existingPerms) {
                xml.root.appendChild("uses-permission") {
                    setAttribute("android:name", "android.permission.FOREGROUND_SERVICE")
                }
            }
            if ("android.permission.FOREGROUND_SERVICE_DATA_SYNC" !in existingPerms) {
                xml.root.appendChild("uses-permission") {
                    setAttribute("android:name", "android.permission.FOREGROUND_SERVICE_DATA_SYNC")
                }
            }

            val application = xml.elements("application").firstOrNull()
                ?: throw IllegalStateException("No <application> element found in AndroidManifest.xml")

            val serviceExists = xml.elements("service").any {
                it.attribute("android:name") == "dev.brave.extension.media.MediaDownloadService"
            }
            if (!serviceExists) {
                application.appendChild("service") {
                    setAttribute("android:name", "dev.brave.extension.media.MediaDownloadService")
                    setAttribute("android:exported", "false")
                    setAttribute("android:foregroundServiceType", "dataSync")
                }
            }
        }

        // 2. Hook ToolbarPhone.onFinishInflate to insert media button (invoke-static/range)
        ToolbarPhoneInflateFingerprint.method.apply {
            val superIdx = instructions.indices.indexOfFirst { instructions[it].opcode == Opcode.INVOKE_SUPER }
            if (superIdx < 0) {
                throw IllegalStateException("Could not find invoke-super in ${definingClass}->$name")
            }
            addInstructions(
                superIdx + 1,
                "invoke-static/range {p0 .. p0}, $TOOLBAR_HOOK->onToolbarInflated(Landroid/view/ViewGroup;)V"
            )
        }

        // 3. Hook ToggleTabStackButton.i(I)V to dynamically extract ColorStateList destination register
        ToolbarTintFingerprint.method.apply {
            val callIdx = instructions.indices.indexOfFirst { idx ->
                val inst = instructions[idx]
                if (inst.opcode != Opcode.INVOKE_STATIC) return@indexOfFirst false
                val ref = (inst as? ReferenceInstruction)?.reference as? MethodReference ?: return@indexOfFirst false
                ref.returnType == "Landroid/content/res/ColorStateList;"
            }
            if (callIdx < 0 || callIdx + 1 >= instructions.size || instructions[callIdx + 1].opcode != Opcode.MOVE_RESULT_OBJECT) {
                throw IllegalStateException("Could not find ColorStateList return & move-result-object in ${definingClass}->$name")
            }
            val moveInst = instructions[callIdx + 1] as OneRegisterInstruction
            val colorReg = moveInst.registerA

            addInstructions(
                callIdx + 2,
                "invoke-static/range {v$colorReg .. v$colorReg}, $TOOLBAR_HOOK->onTintChanged(Landroid/content/res/ColorStateList;)V"
            )
        }

        // 4. Hook ToolbarPhone.d3(Z)V to toggle button visibility during Omnibox typing animation
        ToolbarUrlFocusFingerprint.method.apply {
            addInstructions(
                0,
                "invoke-static/range {p1 .. p1}, $TOOLBAR_HOOK->onUrlFocusChanged(Z)V"
            )
        }
    }
}
