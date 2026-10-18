package yos.music.player.ui.pages.settings.storage

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.data.objects.AppStorage
import yos.music.player.data.objects.CachedSongs
import yos.music.player.ui.pages.library.OnlineListItemDivider
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.OptionDialog
import yos.music.player.ui.widgets.basic.ShadowImageWithCache
import yos.music.player.ui.widgets.basic.Title

/**
 * 缓存分类的「按歌曲挑选清理」页（category = "lyric" / "audio"）。
 *
 * 列表 = 该类别缓存里所有歌曲（按大小倒序），多选后只清所选。单选即点一下，全选一键切换。
 * 图片类不支持此页（Coil 按封面 URL 存、同专辑共用）。
 *
 * 布局要点：全选/清理放在 Title 顶栏（rightBarIcon，常驻可见，且不会被 App 的迷你条/底栏遮挡）；
 * 确认弹窗放在 LazyList 之外（覆盖层）——放进列表项时，屏幕外的项不会被组合，会导致
 * 「点了清理不弹窗」。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CacheDetail(navController: NavController, category: String) {
    val appCategory = when (category) {
        "lyric" -> AppStorage.Category.LYRIC
        else -> AppStorage.Category.AUDIO
    }
    val titleRes = when (appCategory) {
        AppStorage.Category.LYRIC -> R.string.settings_storage_cache_lyric
        else -> R.string.settings_storage_cache_audio
    }

    SettingBackground {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val cacheCleared = stringResource(id = R.string.settings_storage_cache_cleared)
        val empty = stringResource(id = R.string.settings_storage_empty)
        val unknown = stringResource(id = R.string.settings_storage_unknown_song)

        var songs by remember { mutableStateOf<List<CachedSongs.CachedSong>>(emptyList()) }
        var loading by remember { mutableStateOf(true) }
        val selected = remember { mutableStateListOf<String>() }
        var confirmClear by remember { mutableStateOf(false) }

        fun load() {
            scope.launch {
                loading = true
                val list = withContext(Dispatchers.IO) { CachedSongs.build(context, appCategory) }
                songs = list
                selected.clear()
                loading = false
            }
        }

        LaunchedEffect(appCategory) { load() }

        val allSelected = songs.isNotEmpty() && selected.size >= songs.size
        val content = MaterialTheme.colorScheme.onBackground
        val primary = MaterialTheme.colorScheme.primary

        Title(
            title = stringResource(id = titleRes),
            subTitle = stringResource(id = R.string.settings_storage_selected_count, selected.size),
            onBack = { navController.popBackStack() },
            titleHorizontalPadding = 32.dp,
            rightBarIcon = {
                Text(
                    text = stringResource(
                        id = if (allSelected) R.string.settings_storage_select_none
                        else R.string.settings_storage_select_all
                    ),
                    fontSize = 14.sp,
                    color = primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            if (allSelected) {
                                selected.clear()
                            } else {
                                selected.clear()
                                songs.forEach { selected.add(it.hash) }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                )
                Text(
                    text = stringResource(id = R.string.settings_storage_clear_selected),
                    fontSize = 14.sp,
                    color = if (selected.size > 0) primary else content.copy(alpha = 0.3f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = selected.size > 0) { confirmClear = true }
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                )
            },
            content = {
                if (!loading && songs.isEmpty()) {
                    item("empty") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 120.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = empty,
                                fontSize = 15.sp,
                                color = content.copy(alpha = 0.5f)
                            )
                        }
                    }
                }

                itemsIndexed(
                    items = songs,
                    key = { _, song -> song.hash }
                ) { index, song ->
                    CacheSongRow(
                        title = song.title ?: "$unknown · ${song.hash.take(8)}",
                        artist = song.artist,
                        thumb = song.thumb,
                        sizeText = AppStorage.format(context, song.sizeBytes),
                        selected = song.hash in selected,
                        onClick = {
                            if (song.hash in selected) selected.remove(song.hash)
                            else selected.add(song.hash)
                        }
                    )
                    if (index < songs.size - 1) OnlineListItemDivider()
                }
            }
        )

        // 确认弹窗（覆盖层）：不放进 LazyList，保证一定被组合。
        if (confirmClear) {
            val count = selected.size
            OptionDialog(
                icon = {},
                title = stringResource(id = R.string.settings_storage_clear_selected),
                subTitle = stringResource(
                    id = R.string.settings_storage_clear_selected_confirm,
                    count
                ),
                content = null,
                positiveContent = stringResource(id = R.string.common_ok),
                negativeContent = stringResource(id = R.string.common_cancel),
                destructive = true,
                onPositive = {
                    confirmClear = false
                    val hashes = selected.toList()
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            CachedSongs.delete(context, appCategory, hashes)
                        }
                        load()
                        Toast.makeText(context, cacheCleared, Toast.LENGTH_SHORT).show()
                    }
                },
                onNegative = { confirmClear = false },
                onDismissRequest = { confirmClear = false }
            )
        }
    }
}

/** 缓存歌曲行：勾选圆点 + 封面 + 歌名/歌手 + 大小。整行点击切换选中。 */
@Composable
private fun CacheSongRow(
    title: String,
    artist: String?,
    thumb: Uri?,
    sizeText: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) primary.copy(alpha = 0.16f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(21.dp)
                .clip(RoundedCornerShape(50))
                .background(if (selected) primary else Color.Transparent, RoundedCornerShape(50))
                .border(
                    width = 1.5.dp,
                    color = if (selected) primary else Color.White.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(50)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_checkmark),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        ShadowImageWithCache(
            dataLambda = { thumb },
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            cornerRadius = 3.5.dp,
            shadowAlpha = 0f,
            imageQuality = ImageQuality.LOW
        )
        Column(
            Modifier
                .padding(start = 16.dp)
                .weight(1f)
        ) {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                lineHeight = 16.sp
            )
            Text(
                text = artist ?: "",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(0.5f),
                fontSize = 13.sp,
                lineHeight = 13.sp
            )
        }
        Text(
            text = sizeText,
            fontSize = 13.sp,
            modifier = Modifier
                .padding(start = 8.dp)
                .alpha(0.5f)
        )
    }
}
