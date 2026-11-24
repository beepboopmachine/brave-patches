package dev.brave.patches.core

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import dev.brave.patches.media.ActiveTabSwitchFingerprint
import dev.brave.patches.media.ClassLoaderMismatchFingerprint
import dev.brave.patches.media.NavigationFinishFingerprint
import dev.brave.patches.media.NavigationStartFingerprint
import dev.brave.patches.media.TabDestroyFingerprint
import dev.brave.patches.shared.COMPATIBILITY_BRAVE

private const val CORE_TRACKER = "Ldev/brave/extension/core/ActiveTabTracker;"

@Suppress("unused")
val braveCorePatch = patch(
    name = "Brave Core Infrastructure",
    description = "Provides unified active tab tracking and navigation lifecycle events for Brave patches."
) {
    compatibleWith(COMPATIBILITY_BRAVE)

    extendWith("extensions/brave.mpe")

    execute {
        // 1. Hook ActiveTabProvider.k(Tab) at method entry before any registers can be manipulated
        ActiveTabSwitchFingerprint.method.apply {
            addInstructions(
                0,
                "invoke-static/range {p1 .. p1}, $CORE_TRACKER->onActiveTabChanged(Lorg/chromium/chrome/browser/tab/Tab;)V"
            )
        }

        // 2. Hook NavigationStart in r1k.didStartNavigationInPrimaryMainFrame
        NavigationStartFingerprint.method.apply {
            val tabFieldIdx = instructions.indices.indexOfFirst { idx ->
                val inst = instructions[idx]
                if (inst.opcode != Opcode.IGET_OBJECT) return@indexOfFirst false
                val fieldRef = (inst as? ReferenceInstruction)?.reference as? FieldReference ?: return@indexOfFirst false
                fieldRef.type == "Lorg/chromium/chrome/browser/tab/TabImpl;"
            }
            if (tabFieldIdx < 0) {
                throw IllegalStateException("Could not find TabImpl iget-object in ${definingClass}->$name")
            }
            val tabReg = (instructions[tabFieldIdx] as OneRegisterInstruction).registerA
            addInstructions(
                tabFieldIdx + 1,
                "invoke-static {v$tabReg, p1}, $CORE_TRACKER->onNavigationStarted(Lorg/chromium/chrome/browser/tab/Tab;Ljava/lang/Object;)V"
            )
        }

        // 3. Hook NavigationFinish in r1k.didFinishNavigationInPrimaryMainFrame
        NavigationFinishFingerprint.method.apply {
            val tabFieldIdx = instructions.indices.indexOfFirst { idx ->
                val inst = instructions[idx]
                if (inst.opcode != Opcode.IGET_OBJECT) return@indexOfFirst false
                val fieldRef = (inst as? ReferenceInstruction)?.reference as? FieldReference ?: return@indexOfFirst false
                fieldRef.type == "Lorg/chromium/chrome/browser/tab/TabImpl;"
            }
            if (tabFieldIdx < 0) {
                throw IllegalStateException("Could not find TabImpl iget-object in ${definingClass}->$name")
            }
            val tabReg = (instructions[tabFieldIdx] as OneRegisterInstruction).registerA
            addInstructions(
                tabFieldIdx + 1,
                "invoke-static {v$tabReg, p1}, $CORE_TRACKER->onNavigationFinished(Lorg/chromium/chrome/browser/tab/Tab;Ljava/lang/Object;)V"
            )
        }

        // 4. Hook TabDestroy in TabImpl.destroyInternal(Z)I at method entry
        TabDestroyFingerprint.method.apply {
            addInstructions(
                0,
                "invoke-static/range {p0 .. p0}, $CORE_TRACKER->onTabDestroyed(Lorg/chromium/chrome/browser/tab/Tab;)V"
            )
        }

        // 5. Bypass Chromium debug ClassLoader mismatch assertion in wp3.attachBaseContext
        ClassLoaderMismatchFingerprint.method.apply {
            val moveIdx = instructions.indices.indexOfFirst { idx ->
                val inst = instructions[idx]
                inst.opcode == Opcode.MOVE_RESULT &&
                    idx > 0 &&
                    (instructions[idx - 1] as? ReferenceInstruction)?.reference?.toString()?.contains("equals") == true
            }
            if (moveIdx >= 0) {
                val reg = (instructions[moveIdx] as OneRegisterInstruction).registerA
                addInstructions(
                    moveIdx + 1,
                    "const/4 v$reg, 0x1"
                )
            }
        }

        // 6. Clean up split-APK metadata to allow standalone installation
        resourceXml("AndroidManifest.xml").use { xml ->
            xml.root.removeAttribute("android:isolatedSplits")
            xml.root.removeAttribute("android:requiredSplitTypes")
            xml.root.removeAttribute("android:splitTypes")
            xml.root.removeAttribute("android:isSplitRequired")

            xml.elements("application").firstOrNull()?.let { app ->
                app.removeAttribute("android:isSplitRequired")
            }

            xml.elements("meta-data").toList().forEach { meta ->
                val name = meta.attribute("android:name")
                if (name == "com.android.vending.splits.required" || name == "com.android.vending.splits") {
                    meta.remove()
                }
            }
        }
    }
}
