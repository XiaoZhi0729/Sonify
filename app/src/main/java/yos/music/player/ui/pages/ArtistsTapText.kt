package yos.music.player.ui.pages

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import yos.music.player.data.libraries.toMultipleArtists
import yos.music.player.ui.widgets.effects.overlayEffect

private const val ARTIST_TAG = "artist"

/**
 * 播放页歌手名文本：整串渲染保持原文不变，仅对每个歌手的字符区间加不可见注解，
 * 点击时按命中位置回调对应歌手（点谁跳谁）；落在分隔符/空白处兜底回第一歌手。
 *
 * [artistsName] 为 null（占位态）时不挂手势，纯展示。
 */
@Composable
fun ArtistsTapText(
    artistsName: String?,
    placeholder: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 18.5.sp,
    onArtistClick: (String) -> Unit = {}
) {
    val raw = artistsName ?: placeholder
    val annotated = remember(raw) { raw.toArtistsAnnotatedString() }
    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text = annotated,
        fontSize = fontSize,
        modifier = modifier
            .then(
                if (artistsName != null) Modifier.pointerInput(annotated) {
                    detectTapGestures { pos ->
                        val result = layoutResult ?: return@detectTapGestures
                        val offset = result.getOffsetForPosition(pos)
                        val hit = annotated.getStringAnnotations(ARTIST_TAG, offset, offset)
                            .firstOrNull()?.item
                            ?: raw.toMultipleArtists().firstOrNull()?.trim().orEmpty()
                        if (hit.isNotEmpty()) onArtistClick(hit)
                    }
                } else Modifier
            )
            .overlayEffect(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = Color.White.copy(alpha = 0.35f),
        onTextLayout = { layoutResult = it }
    )
}

/**
 * 与 String.toMultipleArtists 同款规则（出现最多的分隔符）在原文上定位各歌手 token 的
 * 字符区间；无分隔符时整串（trim 后）整体标注。原文逐字符 append，渲染结果不变。
 */
private fun String.toArtistsAnnotatedString(): AnnotatedString = buildAnnotatedString {
    append(this@toArtistsAnnotatedString)
    val delimiters = listOf("、", "/", "&", ";", "；", ",")
    var best: String? = null
    var bestCount = 0
    for (delimiter in delimiters) {
        val count = this@toArtistsAnnotatedString.split(delimiter).size - 1
        if (count > bestCount) {
            bestCount = count
            best = delimiter
        }
    }
    fun addToken(token: String, tokenStart: Int) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        val start = tokenStart + token.indexOf(trimmed)
        addStringAnnotation(ARTIST_TAG, trimmed, start, start + trimmed.length)
    }
    if (best == null || bestCount == 0) {
        addToken(this@toArtistsAnnotatedString, 0)
        return@buildAnnotatedString
    }
    val raw = this@toArtistsAnnotatedString
    var idx = 0
    while (idx <= raw.lastIndex) {
        val next = raw.indexOf(best, idx)
        val tokenEnd = if (next == -1) raw.length else next
        addToken(raw.substring(idx, tokenEnd), idx)
        if (next == -1) break
        idx = next + best.length
    }
}
