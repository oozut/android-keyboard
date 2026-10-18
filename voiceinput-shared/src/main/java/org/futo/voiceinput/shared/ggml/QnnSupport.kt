/*
 * Copyright (C) 2025 FUTO Holdings, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.futo.voiceinput.shared.ggml

import android.os.Build

/**
 * Which Qualcomm chipsets the QNN (Hexagon HTP) voice engine can run on.
 *
 * A QNN graph is compiled for one Hexagon DSP architecture, so a Whisper build
 * only runs on the chipsets Qualcomm published that asset for. The allow-list
 * below is Qualcomm's own, taken from two files rather than guessed:
 *
 *  1. Which chipsets have Whisper assets at all — the release manifest of the
 *     model this engine targets (`precisions[*].chipset_assets` keys):
 *
 *       https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo/raw/main/release_assets.json
 *
 *  2. Which Android SoC part number each chipset slug corresponds to — Qualcomm's
 *     device list, `chipsets[<slug>].aliases` (plus `htp_version` and `soc_model`),
 *     from the same AI Hub Models release:
 *
 *       https://github.com/quic/ai-hub-models/blob/v0.62.2/src/qai_hub_models/devices_and_chipsets.yaml
 *
 * Regenerating both after an upstream release (the second URL's part number is in
 * the `aliases` lists, upper-cased):
 *
 *   V=0.62.2
 *   curl -sSL -o release_assets.json \
 *     https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo/raw/main/release_assets.json
 *   curl -sSL -o devices_and_chipsets.yaml \
 *     https://raw.githubusercontent.com/quic/ai-hub-models/v$V/src/qai_hub_models/devices_and_chipsets.yaml
 *   jq -r '.precisions[].chipset_assets | keys[]' release_assets.json | sort -u
 *
 * The manifest names AI Hub devices, never Android SoC strings, so the mapping to
 * a part number comes from column 2 — with one exception worth spelling out.
 * There is no `qualcomm-snapdragon-8gen2` entry in the manifest; the assets for
 * the Snapdragon 8 Gen 2 — the Galaxy S23 — are published under
 * `qualcomm-qcs8550-proxy`, AI Hub's stand-in device. That it really is the same
 * silicon is Qualcomm's own data: `qualcomm-qcs8550-proxy` and
 * `qualcomm-snapdragon-8gen2` both carry `soc_model: 43`, and the latter lists
 * `sm8550` as an alias with `reference_device: Samsung Galaxy S23`.
 *
 * Chipsets the manifest covers but Android cannot run on are deliberately absent
 * (`snapdragon-x-elite`, `snapdragon-x2-elite`: Windows-on-Snapdragon laptops, so
 * no Android device ever reports them). Chipsets Qualcomm supports on Android but
 * publishes no Whisper assets for are absent by construction — notably Snapdragon
 * 8+ Gen 1 (`sm8475`) and every 7-series — because there would be no model to run.
 *
 * Matching is by part number — [Build.SOC_MODEL] on Android 12+, falling back to
 * [Build.BOARD] / [Build.HARDWARE] for automotive boards that report the part
 * number there instead. Nothing but a part number is accepted, so a ROM that
 * reports only a marketing name or a platform code name is treated as
 * unsupported. That is the safe direction: the QNN option is hidden rather than
 * offered and then failing at load time. (Requiring Android 12+ costs nothing
 * here, since the oldest mobile chipset in the manifest is the 8 Gen 1, which
 * shipped with it.)
 */
object QnnSupport {

    /**
     * One chipset from Qualcomm's Whisper release manifest.
     *
     * @param aiHubId chipset slug, verbatim from the manifest, so the table can be
     *                diffed against it.
     * @param htpVersion Hexagon architecture, from `htp_version` in Qualcomm's
     *                device list. Used to check that this build packaged the
     *                matching `libQnnHtpV<N>Skel.so`.
     * @param socModels values [Build.SOC_MODEL] reports for devices on this
     *                chipset (upper-cased; matched ignoring Qualcomm's `-AB` /
     *                `-AC` / `-AD` part-number suffix).
     */
    private data class SupportedChipset(
        val aiHubId: String,
        val htpVersion: Int,
        val socModels: List<String>
    )

    /**
     * The chipsets with published Whisper-Large-V3-Turbo assets that an Android
     * device can actually be — mobile chipsets first, then automotive/IoT.
     *
     * The automotive/IoT entries only ship a `qnn_context_binary` rather than a
     * `precompiled_qnn_onnx` archive. That single-file kind is recognized by the
     * importer but cannot be loaded by the two-session engine (see
     * `ModelQnnFileFile`), so those entries are listed for completeness of
     * Qualcomm's manifest rather than because a runnable model exists for them
     * today; a device on one is treated as QNN-capable only once a
     * `precompiled_qnn_onnx` archive is assigned.
     */
    private val SUPPORTED_CHIPSETS = listOf(
        SupportedChipset("qualcomm-snapdragon-8gen1", htpVersion = 69, socModels = listOf("SM8450")),
        // AI Hub's stand-in for Snapdragon 8 Gen 2 / SM8550 — see the note above.
        SupportedChipset(
            "qualcomm-qcs8550-proxy",
            htpVersion = 73,
            socModels = listOf("SM8550", "QCS8550")
        ),
        SupportedChipset("qualcomm-snapdragon-8gen3", htpVersion = 75, socModels = listOf("SM8650")),
        SupportedChipset("qualcomm-qcs9075", htpVersion = 73, socModels = listOf("QCS9075")),
        // `sm8750-ac` / `sm8850-ad`: Samsung's SKUs, reported as the base part
        // number once the suffix is stripped.
        SupportedChipset(
            "qualcomm-snapdragon-8-elite-for-galaxy",
            htpVersion = 79,
            socModels = listOf("SM8750")
        ),
        SupportedChipset(
            "qualcomm-snapdragon-8-elite-gen5-for-galaxy",
            htpVersion = 81,
            socModels = listOf("SM8850")
        ),
        SupportedChipset("qualcomm-qcs8275", htpVersion = 75, socModels = listOf("QCS8275")),
        SupportedChipset("qualcomm-sa7255p", htpVersion = 75, socModels = listOf("SA7255P")),
        SupportedChipset("qualcomm-sa8295p", htpVersion = 68, socModels = listOf("SA8295P")),
        SupportedChipset("qualcomm-sa8775p", htpVersion = 73, socModels = listOf("SA8775P"))
    )

    /**
     * Hexagon architecture of the matched chipset (`68`, `69`, `73`, `75`, `79`,
     * `81`), or `null` when the SoC is not one Qualcomm ships Whisper assets for.
     * [BackendSupport] treats a non-null value as "this chipset is supported" and
     * uses the number to confirm the matching `libQnnHtpV<N>Skel.so` made it into
     * this build.
     */
    fun matchedHtpVersion(): Int? = matchedSupportedChipset()?.htpVersion

    private fun matchedSupportedChipset(): SupportedChipset? {
        val candidates = reportedSocModels()
        if (candidates.isEmpty()) return null

        return SUPPORTED_CHIPSETS.firstOrNull { chipset ->
            chipset.socModels.any { it in candidates }
        }
    }

    /**
     * The part numbers this ROM reports, normalised: [Build.SOC_MODEL] first, then
     * [Build.BOARD] / [Build.HARDWARE] as a fallback for automotive boards that
     * report the part number there instead. Qualcomm's device list gives no
     * platform code names, so nothing but a part number is accepted — a device
     * whose ROM reports only a code name is treated as unsupported.
     */
    private fun reportedSocModels(): Set<String> {
        val raw = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL?.let { add(it) }
            }
            Build.BOARD?.let { add(it) }
            Build.HARDWARE?.let { add(it) }
        }
        return raw.mapNotNull { normalizeSocModel(it) }.toSet()
    }

    /**
     * Upper-cases a ROM-supplied identifier and strips Qualcomm's part-number
     * suffix (`sm8550-ab` -> `SM8550`). Returns `null` for values that cannot be
     * a part number, so a marketing name such as `"Snapdragon 8 Gen 2"` is not
     * mistaken for one.
     */
    private fun normalizeSocModel(raw: String): String? {
        val normalized = raw.trim().substringBefore('-').uppercase()
        return normalized.takeIf { candidate ->
            candidate.length in 5..8 && candidate.all { it.isLetterOrDigit() }
        }
    }
}
