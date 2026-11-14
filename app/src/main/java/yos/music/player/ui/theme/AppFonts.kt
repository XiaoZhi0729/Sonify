package yos.music.player.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import yos.music.player.R

/**
 * 手写字体族 —— Apple hello 屏那款笔迹（Macintosh Skeletal Filled）。
 *
 * ── ★★★ 中文为什么必须整串连着写（这是本文件最重要的信息）──────────────
 * 这份字体的中文**不是**按单字映射的：
 *
 *   · cmap 里 `欢 迎 使 用` 四个码位各自指向一个 `.ccmp.uniXXXX` 字形，
 *     而那四个字形是**同一个占位图形**（四条竖直圆角长条 = "斜杠"）。
 *     `你` / `好` 指向的更是**空字形**（0 轮廓）。
 *   · 真字形藏在**连字规则**里（GSUB LookupType 4）：
 *       `.ccmp.uni6B22+uni8FCE+uni4F7F+uni7528` → `welcome.chineseSimplified`（14 轮廓）
 *       `.ccmp.uni4F60+uni597D`                 → `hello.chineseSimplified`（8 轮廓）
 *
 * 也就是说：**只有把「欢迎使用」四个字连在一起、且 `ccmp` 特性生效时**，
 * 整形器才会把它们合成一个完整的词字形。
 *
 * 实测（HarfBuzz，也就是 Android 真用的整形器）：
 *   `欢迎使用` + ccmp 开 → gid 1414 `welcome.chineseSimplified`  ✅
 *   `欢迎使用` + ccmp 关 → 4 个占位字形（四条斜杠）              ❌
 *   `欢迎使`   （少一字） → 3 条斜杠                              ❌
 *
 * 因此调用点必须做到三条，缺一条就退化成斜杠：
 *   ① 文本**正好**是「欢迎使用」四个字（不能拆字、不能中间插空格/ZWNJ）；
 *   ② 显式开启 `ccmp` 特性（见 [HelloTextFeatures]，Compose 默认不一定开）；
 *   ③ **不要设负 `letterSpacing`** —— 负字距会把字形推叠到一起，
 *      部分整形器会因此放弃连字（本工程曾经踩过，表现就是"一堆斜线"）。
 *
 * ── 来源与授权 ─────────────────────────────────────────────────
 * 文件来自 `Macintosh-Skeletal-Filled` 项目（用户指定）：
 * 重建 Apple macOS 27 / iOS 27 的 "Macintosh Skeletal" hello 屏笔迹。
 * LICENSE 仅限个人/非商业/教育，**禁止作为商业产品的一部分再分发**；
 * 对外发布前必须换成 SIL OFL 近似体或取得书面授权。
 *
 * ── 使用范围 ───────────────────────────────────────────────────
 * **只在引导页首屏 / 末屏的大字上用**，刻意不写进全局 `Typography`。
 */
val HelloFontFamily = FontFamily(
    Font(R.font.macintosh_skeletal_filled, FontWeight.Normal)
)

/**
 * 手写体必须开启的 OpenType 特性。
 *
 * `ccmp`（Glyph Composition/Decomposition）是中文真字形的唯一入口，
 * 见 [HelloFontFamily] 的说明。`calt` / `liga` / `rlig` 负责拉丁连笔
 * （"Sonify" 的字母要连起来才像手写），一并打开。
 *
 * 用法：
 * ```
 * Text(
 *     text = ...,
 *     fontFamily = HelloFontFamily,
 *     style = TextStyle(fontFeatureSettings = HelloTextFeatures),
 * )
 * ```
 */
const val HelloTextFeatures: String = "'ccmp' 1, 'calt' 1, 'liga' 1, 'rlig' 1"
