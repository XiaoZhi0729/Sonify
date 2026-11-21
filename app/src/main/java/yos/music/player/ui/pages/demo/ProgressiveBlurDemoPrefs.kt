package yos.music.player.ui.pages.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import yos.music.player.ui.widgets.effects.progressiveblur.DemoBackgroundMode
import yos.music.player.ui.widgets.effects.progressiveblur.ProgressiveBlurConfig
import yos.music.player.ui.widgets.effects.progressiveblur.ProgressiveBlurMode
import yos.music.player.ui.widgets.effects.progressiveblur.ProgressiveCurveType
import yos.music.player.ui.widgets.effects.progressiveblur.ProgressiveOrientationMode
import yos.music.player.ui.widgets.effects.progressiveblur.ResolvedMirrorSplits
import java.util.Locale

internal object ProgressiveBlurDemoPrefs {

    private const val MMKV_ID = "sonify_progressive_blur_demo"
    private const val KEY_BG_MODE = "v2_demo_bg_mode"

    const val SLOT_TABLET_LANDSCAPE = "v2_tablet_landscape"
    const val SLOT_TABLET_PORTRAIT = "v2_tablet_portrait"
    const val SLOT_PHONE_LANDSCAPE = "v2_phone_landscape"
    const val SLOT_PHONE_PORTRAIT = "v2_phone_portrait"

    private val gson = Gson()
    private val store by lazy {
        runCatching { MMKV.mmkvWithID(MMKV_ID) }.getOrNull()
    }

    /** Bumped on [save] / [reset] so mainline PlayerShell reloads edited slot parameters live. */
    var configVersion by mutableIntStateOf(0)
        private set

    private data class StoredConfig(
        val orientationMode: String? = null,
        val blurMode: String? = null,
        val curveType: String? = null,
        val coverSize: Float? = null,
        val blurStart: Float? = null,
        val blurEnd: Float? = null,
        val blurStartY: Float? = null,
        val blurEndY: Float? = null,
        val maxBlurLevel: Float? = null,
        val ambientBlend: Float? = null,
        val curvePower: Float? = null,
        val scrimMaxAlpha: Float? = null,
        val scrimPower: Float? = null,
        val blurSaturation: Float? = null,
        val showDebugGuides: Boolean? = null,
    )

    fun load(slotKey: String, default: ProgressiveBlurConfig): ProgressiveBlurConfig {
        val raw = runCatching { store?.decodeString(slotKey) }.getOrNull() ?: return default
        val parsed = runCatching { gson.fromJson(raw, StoredConfig::class.java) }.getOrNull()
            ?: return default
        return ProgressiveBlurConfig(
            orientationMode = parsed.orientationMode
                ?.let { name -> runCatching { ProgressiveOrientationMode.valueOf(name) }.getOrNull() }
                ?: default.orientationMode,
            blurMode = parsed.blurMode
                ?.let { name -> runCatching { ProgressiveBlurMode.valueOf(name) }.getOrNull() }
                ?: default.blurMode,
            curveType = parsed.curveType
                ?.let { name -> runCatching { ProgressiveCurveType.valueOf(name) }.getOrNull() }
                ?: default.curveType,
            coverSize = parsed.coverSize ?: default.coverSize,
            blurStart = parsed.blurStart ?: default.blurStart,
            blurEnd = parsed.blurEnd ?: default.blurEnd,
            blurStartY = parsed.blurStartY ?: default.blurStartY,
            blurEndY = parsed.blurEndY ?: default.blurEndY,
            maxBlurLevel = parsed.maxBlurLevel ?: default.maxBlurLevel,
            ambientBlend = parsed.ambientBlend ?: default.ambientBlend,
            curvePower = parsed.curvePower ?: default.curvePower,
            scrimMaxAlpha = parsed.scrimMaxAlpha ?: default.scrimMaxAlpha,
            scrimPower = parsed.scrimPower ?: default.scrimPower,
            blurSaturation = parsed.blurSaturation ?: default.blurSaturation,
            showDebugGuides = parsed.showDebugGuides ?: default.showDebugGuides,
        ).sanitized()
    }

    fun save(slotKey: String, config: ProgressiveBlurConfig) {
        val clean = config.sanitized()
        val dto = StoredConfig(
            orientationMode = clean.orientationMode.name,
            blurMode = clean.blurMode.name,
            curveType = clean.curveType.name,
            coverSize = clean.coverSize,
            blurStart = clean.blurStart,
            blurEnd = clean.blurEnd,
            blurStartY = clean.blurStartY,
            blurEndY = clean.blurEndY,
            maxBlurLevel = clean.maxBlurLevel,
            ambientBlend = clean.ambientBlend,
            curvePower = clean.curvePower,
            scrimMaxAlpha = clean.scrimMaxAlpha,
            scrimPower = clean.scrimPower,
            blurSaturation = clean.blurSaturation,
            showDebugGuides = clean.showDebugGuides,
        )
        runCatching {
            store?.encode(slotKey, gson.toJson(dto))
        }
        configVersion += 1
    }

    fun reset(slotKey: String, default: ProgressiveBlurConfig): ProgressiveBlurConfig {
        val clean = default.sanitized()
        save(slotKey, clean)
        return clean
    }

    fun loadBackgroundMode(): DemoBackgroundMode {
        val raw = runCatching { store?.decodeString(KEY_BG_MODE) }.getOrNull() ?: return DemoBackgroundMode.OFF
        return runCatching { DemoBackgroundMode.valueOf(raw) }.getOrDefault(DemoBackgroundMode.OFF)
    }

    fun saveBackgroundMode(mode: DemoBackgroundMode) {
        runCatching {
            store?.encode(KEY_BG_MODE, mode.name)
        }
    }

    fun formatCopyText(
        slotLabel: String,
        config: ProgressiveBlurConfig,
        splits: ResolvedMirrorSplits,
        bgMode: DemoBackgroundMode = DemoBackgroundMode.OFF,
    ): String {
        val c = config.sanitized()
        return buildString {
            appendLine("[$slotLabel]")
            appendLine(
                "bgMode=${bgMode.name}, orientationMode=${c.orientationMode.name}, blurMode=${c.blurMode.name}, curve=${c.curveType.name}",
            )
            appendLine(
                String.format(
                    Locale.US,
                    "coverSize=%.2f (1:1 -> splitX=%.2f, splitY=%.2f)",
                    c.coverSize,
                    splits.splitX,
                    splits.splitY,
                ),
            )
            appendLine(
                String.format(
                    Locale.US,
                    "blurStart=%.2f, blurEnd=%.2f",
                    c.blurStart,
                    c.blurEnd,
                ),
            )
            if (c.orientationMode == ProgressiveOrientationMode.TOP_LEFT_2D) {
                appendLine(
                    String.format(
                        Locale.US,
                        "Y: blurStartY=%.2f, blurEndY=%.2f",
                        c.blurStartY,
                        c.blurEndY,
                    ),
                )
            }
            append(
                String.format(
                    Locale.US,
                    "maxBlurLevel=%.2f, ambientBlend=%.2f, curvePower=%.2f, scrimMaxAlpha=%.2f, scrimPower=%.2f, blurSaturation=%.2f",
                    c.maxBlurLevel,
                    c.ambientBlend,
                    c.curvePower,
                    c.scrimMaxAlpha,
                    c.scrimPower,
                    c.blurSaturation,
                ),
            )
        }
    }
}
