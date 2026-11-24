package dev.brave.patches.perf

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.deleteResource
import app.morphe.patcher.patch.listApkEntries
import app.morphe.patcher.patch.patch
import dev.brave.patches.shared.COMPATIBILITY_BRAVE

internal object P3aSerializationFingerprint : Fingerprint(
    definingClass = "Lp3a;",
    name = "a",
    returnType = "V",
    parameters = listOf("Lmt6;")
)

internal object UmaSessionStatsFingerprint : Fingerprint(
    definingClass = "Lorg/chromium/chrome/browser/metrics/UmaSessionStats;",
    name = "changeMetricsReportingConsent",
    returnType = "V",
    parameters = listOf("Z", "Z")
)

@Suppress("unused")
val telemetryAndLocaleTrimPatch = patch(
    name = "Telemetry & Locale Trim",
    description = "Disables P3A telemetry serialization, metrics reporting consent, and prunes unused locale resources."
) {
    compatibleWith(COMPATIBILITY_BRAVE)

    execute {
        // 1. Prepend return-void to Lp3a;->a(Lmt6;)V to neutralize P3A serialization
        P3aSerializationFingerprint.method.apply {
            addInstructions(0, "return-void")
        }

        // 2. Force changeMetricsReportingConsent(ZZ)V parameters to false if method exists
        UmaSessionStatsFingerprint.methodOrNull?.apply {
            addInstructions(
                0,
                """
                    const/4 p0, 0x0
                    const/4 p1, 0x0
                """.trimIndent()
            )
        }

        // 3. Prune non-English locale resource directories, strictly preserving the standard non-locale configurations
        // derived from recon_raw/delta/xml/locale_dirs.txt
        val preservedNonLocaleConfigs = setOf(
            "values",
            "values-h360dp-land", "values-h480dp", "values-h480dp-land", "values-h500dp",
            "values-h568dp", "values-h720dp", "values-h748dp", "values-hdpi", "values-land",
            "values-large", "values-ldrtl", "values-night", "values-port", "values-sw1200dp",
            "values-sw240dp", "values-sw280dp", "values-sw360dp", "values-sw600dp",
            "values-sw600dp-h1000dp-port", "values-sw600dp-h1200dp-port", "values-sw600dp-h700dp-port",
            "values-sw600dp-h800dp-port", "values-sw600dp-h900dp-port", "values-sw600dp-v30",
            "values-sw600dp-w600dp", "values-sw720dp", "values-v31", "values-v33", "values-v34",
            "values-v35", "values-w1000dp", "values-w1200dp", "values-w360dp", "values-w360dp-port",
            "values-w400dp-port", "values-w412dp", "values-w600dp", "values-w800dp", "values-xlarge"
        )

        fun shouldPreserve(dirName: String): Boolean {
            if (dirName.startsWith("values-en")) return true
            return preservedNonLocaleConfigs.contains(dirName)
        }

        listApkEntries("res/values-").forEach { entry ->
            val parts = entry.removePrefix("res/").split("/")
            val dirName = parts.firstOrNull() ?: return@forEach
            if (!shouldPreserve(dirName)) {
                deleteResource(entry)
            }
        }

        // 4. Prune large non-English Chromium locale .pak files (preserving lang_en, fallback-locales, and tiny gender variants)
        listApkEntries("assets/locales#lang_").forEach { entry ->
            if (entry.startsWith("assets/locales#lang_en/")) return@forEach
            if (entry.endsWith("_FEMININE.pak") || entry.endsWith("_MASCULINE.pak") || entry.endsWith("_NEUTER.pak")) {
                return@forEach
            }
            if (entry.endsWith(".pak")) {
                deleteResource(entry)
            }
        }
    }
}
