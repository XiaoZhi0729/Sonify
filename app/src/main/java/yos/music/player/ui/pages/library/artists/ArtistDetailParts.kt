package yos.music.player.ui.pages.library.artists

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.R
import yos.music.player.data.repositories.KugouNewAlbum
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.pages.discovery.NewAlbumCard
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.OnlineListItemDivider

internal data class ArtistPagingSnapshot(
    val current: Int,
    val count: Int,
    val page: Int,
    val loading: Boolean,
    val error: String?,
    val endReached: Boolean
)

@Composable
internal fun ArtistSectionHeader(title: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp)
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                modifier = Modifier.size(24.dp).alpha(0.35f),
                tint = LocalContentColor.current
            )
        }
    }
}

@Composable
internal fun ArtistSectionError(error: String, busy: Boolean = false, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text(error, fontSize = 15.sp, modifier = Modifier.alpha(0.65f))
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.artist_detail_retry),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(enabled = !busy, role = Role.Button, onClick = onRetry)
                .padding(vertical = 6.dp).alpha(if (busy) 0.45f else 1f)
        )
    }
}

@Composable
private fun ArtistEmptyStatus(text: String) {
    Text(text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp).alpha(0.6f))
}

@Composable
internal fun ArtistSongSection(
    songs: List<KugouNewSong>,
    loading: Boolean,
    error: String?,
    pagerState: PagerState,
    onRetry: () -> Unit,
    onPlayAt: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        when {
            songs.isNotEmpty() -> SongsPagerSection(songs, pagerState, onPlayAt)
            loading -> ArtistSongsSkeleton()
            error == null -> ArtistEmptyStatus(stringResource(R.string.artist_detail_empty))
        }
        // Appended-page failures remain visible without hiding already loaded songs.
        if (error != null) ArtistSectionError(error, loading, onRetry)
        if (loading && songs.isNotEmpty()) {
            Text(
                stringResource(R.string.online_playlists_loading), fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp).alpha(0.5f)
            )
        }
    }
}

@Composable
internal fun SongsPagerSection(
    songs: List<KugouNewSong>,
    pagerState: PagerState,
    onPlayAt: (Int) -> Unit
) {
    val displaySongs = remember(songs) { songs.map { KugouRepository.toDisplayMediaItem(it) } }
    val pageKeys = remember(songs) {
        songs.chunked(4).mapIndexed { index, column -> "artist-page-$index-${column.first().hash}" }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val startInset = 20.dp
        val endInset = 39.dp
        val pageWidth = (maxWidth - startInset - endInset).coerceAtLeast(1.dp)
        HorizontalPager(
            state = pagerState,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                pagerSnapDistance = PagerSnapDistance.atMost(Int.MAX_VALUE)
            ),
            pageSize = PageSize.Fixed(pageWidth),
            contentPadding = PaddingValues(start = startInset, end = endInset),
            verticalAlignment = Alignment.Top,
            key = { page -> pageKeys.getOrElse(page) { "artist-page-$page-empty" } },
            beyondViewportPageCount = 1
        ) { page ->
            Column(Modifier.fillMaxWidth()) {
                repeat(4) { row ->
                    val index = page * 4 + row
                    val item = displaySongs.getOrNull(index) ?: return@repeat
                    MusicList(item, horizontalPadding = 0.dp) { onPlayAt(index) }
                    if (row < 3 && index + 1 < songs.size) {
                        OnlineListItemDivider(startPadding = 66.dp, endPadding = 16.dp)
                    }
                }
            }
        }
    }
}

/** Shared, static placeholder: four 64dp rows with 52dp artwork. */
@Composable
internal fun ArtistSongsSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        repeat(4) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtistSkeletonBlock(Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArtistSkeletonBlock(Modifier.fillMaxWidth(0.65f).height(13.dp))
                    ArtistSkeletonBlock(Modifier.fillMaxWidth(0.42f).height(11.dp))
                }
            }
        }
    }
}

@Composable
internal fun ArtistHeaderSkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ArtistSkeletonBlock(Modifier.width(150.dp).height(14.dp))
        ArtistSkeletonBlock(Modifier.width(108.dp).height(10.dp))
    }
}

@Composable
private fun ArtistSkeletonBlock(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(LocalContentColor.current.copy(alpha = 0.09f)))
}

@Composable
internal fun ArtistAlbumsSection(
    albums: List<KugouNewAlbum>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onOpen: (KugouNewAlbum) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        when {
            albums.isNotEmpty() -> LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(albums, key = { index, album -> "artist-album-$index-${album.albumId}" }) { _, album ->
                    NewAlbumCard(album = album) { onOpen(album) }
                }
            }
            loading -> LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(3) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        ArtistSkeletonBlock(Modifier.size(150.dp))
                        ArtistSkeletonBlock(Modifier.width(110.dp).height(12.dp))
                        ArtistSkeletonBlock(Modifier.width(80.dp).height(10.dp))
                    }
                }
            }
            error == null -> ArtistEmptyStatus(stringResource(R.string.artist_detail_albums_empty))
        }
        if (error != null) ArtistSectionError(error, loading, onRetry)
    }
}
