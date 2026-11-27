package yos.music.player.ui.pages.discovery

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import me.tatarka.google.material.palettes.TonalPalette
import yos.music.player.R
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.ui.lazyItemKeys
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.mochi.mochiHorizontalOverscroll
import yos.music.player.ui.widgets.mochi.rememberMochiSnapFlingBehavior

internal val MusicStoryCoverWidth = 148.dp
internal val MusicStoryCoverHeight = 202.dp
internal val MusicStoryCoverCornerRadius = 12.dp
internal val MusicStoryCardGap = 16.dp

internal fun calculateMusicStoryCardWidth(containerWidth: Dp): Dp =
    (containerWidth - 72.dp).coerceIn(296.dp, 344.dp)

internal fun formatMusicStoryHeader(index: Int): String =
    "MUSIC\nSTORY%03d".format(index + 1)

internal fun musicStoryAccentColorFromHue(hue: Float, isDark: Boolean): Color {
    val palette = TonalPalette.fromHueAndChroma(hue.toDouble(), if (isDark) 36.0 else 42.0)
    return Color(palette.tone(if (isDark) 78 else 42))
}

@Composable
fun NicheTreasureRail(
    songs: List<KugouNewSong>,
    onSongClick: (index: Int) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val keys = remember(songs) { lazyItemKeys(songs) { it.hash } }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardWidth = calculateMusicStoryCardWidth(maxWidth)
        val endPadding = calculateStartAlignedRailEndPadding(
            containerWidth = maxWidth,
            cardWidth = cardWidth,
            startPadding = 20.dp
        )
        LazyRow(
            state = listState,
            flingBehavior = rememberMochiSnapFlingBehavior(
                lazyListState = listState,
                snapPosition = SnapPosition.Start
            ),
            modifier = Modifier
                .fillMaxWidth()
                .mochiHorizontalOverscroll(),
            contentPadding = PaddingValues(start = 20.dp, end = endPadding),
            horizontalArrangement = Arrangement.spacedBy(MusicStoryCardGap)
        ) {
            itemsIndexed(
                items = songs,
                key = { index, _ -> keys.getOrElse(index) { "oob_$index" } },
                contentType = { _, _ -> "music_story_card" }
            ) { index, song ->
                MusicStoryCard(
                    index = index,
                    song = song,
                    cardWidth = cardWidth,
                    onClick = { onSongClick(index) }
                )
            }
        }
    }
}

@Composable
private fun MusicStoryCard(
    index: Int,
    song: KugouNewSong,
    cardWidth: Dp,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isFlamingoInDarkMode()
    val extractedHue = rememberFmHue(song.artworkUrl)
    val targetAccent = if (extractedHue != null) {
        musicStoryAccentColorFromHue(extractedHue, isDark)
    } else {
        MaterialTheme.colorScheme.primary
    }
    val accentColor by animateColorAsState(
        targetValue = targetAccent,
        animationSpec = tween(260),
        label = "musicStoryAccent"
    )
    val coverShape = remember { YosRoundedCornerShape(MusicStoryCoverCornerRadius) }

    val storyQuote = remember(song) {
        song.recommendInfo?.takeIf { it.isNotBlank() }
            ?: song.channelName?.takeIf { it.isNotBlank() }
            ?: song.albumName?.takeIf { it.isNotBlank() }
    }
    val recommenderName = song.recommenderName?.takeIf { it.isNotBlank() }
    val pillText = when {
        recommenderName != null -> stringResource(R.string.niche_treasure_recommended_by, recommenderName)
        else -> song.channelName?.takeIf { it.isNotBlank() && it != storyQuote }
    }

    Row(
        modifier = Modifier
            .width(cardWidth)
            .height(MusicStoryCoverHeight)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = remember(song.artworkUrl, context) {
                ImageRequest.Builder(context)
                    .data(song.artworkUrl)
                    .size(440)
                    .precision(Precision.INEXACT)
                    .apply {
                        song.artworkUrl?.let { memoryCacheKey("music-story-cover:$it") }
                    }
                    .crossfade(true)
                    .placeholder(R.drawable.placeholder_music_default_artwork)
                    .error(R.drawable.placeholder_music_default_artwork)
                    .fallback(R.drawable.placeholder_music_default_artwork)
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(MusicStoryCoverWidth)
                .fillMaxHeight()
                .clip(coverShape)
                .border(
                    width = 0.5.dp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    shape = coverShape
                )
        )

        Spacer(Modifier.width(14.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(top = 4.dp)
        ) {
            Text(
                text = formatMusicStoryHeader(index),
                fontWeight = FontWeight.Black,
                fontSize = 23.sp,
                lineHeight = 25.sp,
                letterSpacing = (-0.4).sp,
                color = accentColor,
                maxLines = 2
            )

            Spacer(Modifier.height(10.dp))

            Text(
                text = song.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 17.sp,
                lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(3.dp))

            Text(
                text = song.author,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (storyQuote != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = storyQuote,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (pillText != null) {
                val hasAvatar = song.recommenderAvatarUrl != null
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.11f else 0.06f))
                        .padding(
                            start = if (hasAvatar) 3.dp else 10.dp,
                            end = 10.dp,
                            top = 3.dp,
                            bottom = 3.dp
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasAvatar) {
                        AsyncImage(
                            model = remember(song.recommenderAvatarUrl, context) {
                                ImageRequest.Builder(context)
                                    .data(song.recommenderAvatarUrl)
                                    .size(64)
                                    .precision(Precision.INEXACT)
                                    .crossfade(true)
                                    .build()
                            },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = pillText,
                        fontSize = 12.sp,
                        lineHeight = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
