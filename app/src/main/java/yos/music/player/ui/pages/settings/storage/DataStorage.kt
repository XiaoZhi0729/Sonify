package yos.music.player.ui.pages.settings.storage

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.objects.AppStorage
import yos.music.player.ui.UI
import yos.music.player.ui.pages.settings.Divider
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SelectItem
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.widgets.basic.OptionDialog
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title

/** 音频缓存上限档位（MB），与 [SettingsLibrary.AudioCacheCapMb] 配对。 */
private val AUDIO_CAP_LABELS = listOf("256 MB", "512 MB", "1 GB", "2 GB")
private val AUDIO_CAP_VALUES = listOf(256, 512, 1024, 2048)

/** 图片缓存上限档位（MB），与 [SettingsLibrary.ImageCacheCapMb] 配对。 */
private val IMAGE_CAP_LABELS = listOf("128 MB", "256 MB", "512 MB", "1 GB")
private val IMAGE_CAP_VALUES = listOf(128, 256, 512, 1024)

/** 把当前 MB 值映射到最接近的档位标签（防设置值与档位表漂移时显示为空）。 */
private fun capLabel(labels: List<String>, values: List<Int>, mb: Int): String {
    var best = 0
    var bestDiff = Int.MAX_VALUE
    values.forEachIndexed { i, v ->
        val d = kotlin.math.abs(v - mb)
        if (d < bestDiff) {
            bestDiff = d
            best = i
        }
    }
    return labels[best]
}

/**
 * 设置 > 数据与储存。
 *
 * 顶部用量卡 + 缓存分类（歌词/音频/图片/其它，各显示大小，音频/图片附"已用/上限"）+
 * 缓存上限（用户可调，下次启动生效）+ 清除全部缓存。
 * 歌词与音频可点进二级页「按歌曲挑选清理」。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DataStorage(navController: NavController) =
    SettingBackground {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val calculating = stringResource(id = R.string.settings_storage_calculating)
        val cacheCleared = stringResource(id = R.string.settings_storage_cache_cleared)
        val clearAllConfirm = stringResource(id = R.string.settings_storage_clear_cache_confirm)

        var usage by remember { mutableStateOf<AppStorage.Usage?>(null) }
        var catSizes by remember { mutableStateOf<Map<AppStorage.Category, Long>>(emptyMap()) }
        var refreshing by remember { mutableStateOf(false) }
        var showClearAll by remember { mutableStateOf(false) }
        var pendingClear by remember { mutableStateOf<AppStorage.Category?>(null) }

        fun refresh() {
            scope.launch {
                refreshing = true
                val result = withContext(Dispatchers.IO) {
                    val u = AppStorage.usage(context)
                    val sizes = AppStorage.Category.values()
                        .associateWith { AppStorage.categoryBytes(context, it) }
                    u to sizes
                }
                usage = result.first
                catSizes = result.second
                refreshing = false
            }
        }

        LaunchedEffect(Unit) { refresh() }

        // 从二级挑选页返回 / 弹窗回来时刷新大小（LaunchedEffect(Unit) 在返回时不重跑）。
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refresh()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        fun sizeText(bytes: Long?) =
            bytes?.let { AppStorage.format(context, it) } ?: calculating

        fun capText(used: Long?, cap: Long) =
            used?.let {
                context.getString(
                    R.string.settings_storage_cache_cap,
                    AppStorage.format(context, it),
                    AppStorage.format(context, cap)
                )
            } ?: calculating

        Title(
            title = stringResource(id = R.string.settings_storage_data_storage),
            onBack = { navController.popBackStack() },
            titleHorizontalPadding = 32.dp,
            onRefresh = { refresh() },
            refreshing = refreshing,
            content = {
                item("storage") {
                    Column(Modifier.fillMaxSize()) {
                        ListHeader(stringResource(id = R.string.settings_storage_usage))
                        RoundColumn {
                            StorageUsageCard(usage = usage, calculating = calculating)
                        }

                        GroupSpacer()

                        ListHeader(stringResource(id = R.string.settings_storage_cache))
                        RoundColumn {
                            LabelItem(
                                title = stringResource(id = R.string.settings_storage_cache_lyric),
                                desc = sizeText(catSizes[AppStorage.Category.LYRIC]),
                                onClick = { navController.navigate(UI.Settings.cacheDetailRoute("lyric")) }
                            )
                            Divider()
                            LabelItem(
                                title = stringResource(id = R.string.settings_storage_cache_audio),
                                desc = capText(
                                    catSizes[AppStorage.Category.AUDIO],
                                    AppStorage.audioCapBytes()
                                ),
                                onClick = { navController.navigate(UI.Settings.cacheDetailRoute("audio")) }
                            )
                            Divider()
                            LabelItem(
                                title = stringResource(id = R.string.settings_storage_cache_image),
                                desc = capText(
                                    catSizes[AppStorage.Category.IMAGE],
                                    AppStorage.imageCapBytes()
                                ),
                                onClick = { pendingClear = AppStorage.Category.IMAGE }
                            )
                            Divider()
                            LabelItem(
                                title = stringResource(id = R.string.settings_storage_cache_other),
                                desc = sizeText(catSizes[AppStorage.Category.OTHER]),
                                onClick = { pendingClear = AppStorage.Category.OTHER }
                            )
                            Divider()
                            LabelItem(
                                title = stringResource(id = R.string.settings_storage_clear_cache),
                                superLink = true,
                                onClick = { showClearAll = true }
                            )
                        }

                        GroupSpacer()

                        ListHeader(stringResource(id = R.string.settings_storage_cache_limit_title))
                        RoundColumn {
                            SelectItem(
                                title = stringResource(id = R.string.settings_storage_audio_cap),
                                items = AUDIO_CAP_LABELS,
                                value = {
                                    capLabel(
                                        AUDIO_CAP_LABELS,
                                        AUDIO_CAP_VALUES,
                                        SettingsLibrary.AudioCacheCapMb
                                    )
                                },
                                onValueChange = { label ->
                                    AUDIO_CAP_VALUES.getOrNull(AUDIO_CAP_LABELS.indexOf(label))
                                        ?.let { SettingsLibrary.AudioCacheCapMb = it }
                                }
                            )
                            Divider()
                            SelectItem(
                                title = stringResource(id = R.string.settings_storage_image_cap),
                                items = IMAGE_CAP_LABELS,
                                value = {
                                    capLabel(
                                        IMAGE_CAP_LABELS,
                                        IMAGE_CAP_VALUES,
                                        SettingsLibrary.ImageCacheCapMb
                                    )
                                },
                                onValueChange = { label ->
                                    IMAGE_CAP_VALUES.getOrNull(IMAGE_CAP_LABELS.indexOf(label))
                                        ?.let { SettingsLibrary.ImageCacheCapMb = it }
                                }
                            )
                        }
                        ListHeader(stringResource(id = R.string.settings_storage_cap_restart_hint))

                        GroupSpacer()

                        val pending = pendingClear
                        if (pending != null) {
                            val name = stringResource(
                                id = when (pending) {
                                    AppStorage.Category.IMAGE -> R.string.settings_storage_cache_image
                                    else -> R.string.settings_storage_cache_other
                                }
                            )
                            OptionDialog(
                                icon = {},
                                title = name,
                                subTitle = stringResource(
                                    id = R.string.settings_storage_clear_category_confirm,
                                    name
                                ),
                                content = null,
                                positiveContent = stringResource(id = R.string.common_ok),
                                negativeContent = stringResource(id = R.string.common_cancel),
                                destructive = true,
                                onPositive = {
                                    pendingClear = null
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            AppStorage.clearCategory(context, pending)
                                        }
                                        refresh()
                                        Toast.makeText(context, cacheCleared, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onNegative = { pendingClear = null },
                                onDismissRequest = { pendingClear = null }
                            )
                        }

                        if (showClearAll) {
                            OptionDialog(
                                icon = {},
                                title = stringResource(id = R.string.settings_storage_clear_cache),
                                subTitle = clearAllConfirm,
                                content = null,
                                positiveContent = stringResource(id = R.string.common_ok),
                                negativeContent = stringResource(id = R.string.common_cancel),
                                destructive = true,
                                onPositive = {
                                    showClearAll = false
                                    scope.launch {
                                        withContext(Dispatchers.IO) { AppStorage.clearCaches(context) }
                                        refresh()
                                        Toast.makeText(context, cacheCleared, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onNegative = { showClearAll = false },
                                onDismissRequest = { showClearAll = false }
                            )
                        }
                    }
                }
            }
        )
    }

/** 用量卡片：分段占比条 + 图例 + 应用/用户数据/缓存/合计四行。 */
@Composable
private fun StorageUsageCard(usage: AppStorage.Usage?, calculating: String) {
    val context = LocalContext.current
    val content = MaterialTheme.colorScheme.onBackground
    val card = MaterialTheme.colorScheme.onSecondary
    val appColor = MaterialTheme.colorScheme.primary
    val dataColor = Color(0xFFFFB900)
    val cacheColor = Color(0xFF00D492)

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        Spacer(Modifier.height(8.dp))
        if (usage != null && usage.totalBytes > 0) {
            StorageBar(
                appBytes = usage.appBytes,
                dataBytes = usage.dataBytes,
                cacheBytes = usage.cacheBytes,
                appColor = appColor,
                dataColor = dataColor,
                cacheColor = cacheColor,
                separator = card
            )
            Spacer(Modifier.height(12.dp))
            StorageLegend(appColor, dataColor, cacheColor, content)
            Spacer(Modifier.height(8.dp))
        }
        StorageInfoRow(
            label = stringResource(id = R.string.settings_storage_app_size),
            value = usage?.let { AppStorage.format(context, it.appBytes) } ?: calculating,
            iconRes = R.drawable.ic_mini_parcel,
            content = content
        )
        StorageInfoRow(
            label = stringResource(id = R.string.settings_storage_user_data),
            value = usage?.let { AppStorage.format(context, it.dataBytes) } ?: calculating,
            iconRes = R.drawable.ic_mini_user,
            content = content
        )
        StorageInfoRow(
            label = stringResource(id = R.string.settings_storage_cache),
            value = usage?.let { AppStorage.format(context, it.cacheBytes) } ?: calculating,
            iconRes = R.drawable.ic_mini_cache,
            content = content
        )
        StorageInfoRow(
            label = stringResource(id = R.string.settings_storage_total),
            value = usage?.let { AppStorage.format(context, it.totalBytes) } ?: calculating,
            iconRes = R.drawable.ic_mini_drive,
            content = content,
            isTotal = true
        )
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * 分段占比条：16dp 高胶囊，段间用卡片底色 2dp 分隔；缓存段最小 8dp 宽（避免过小看不见）。
 * 几何对齐 Cresto `StorageChart`。
 */
@Composable
private fun StorageBar(
    appBytes: Long,
    dataBytes: Long,
    cacheBytes: Long,
    appColor: Color,
    dataColor: Color,
    cacheColor: Color,
    separator: Color
) {
    val total = (appBytes + dataBytes + cacheBytes).toFloat()
    if (total <= 0f) return
    var barWidthPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val minCachePx = with(density) { 8.dp.toPx() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(RoundedCornerShape(50))
            .onGloballyPositioned { barWidthPx = it.size.width }
    ) {
        if (appBytes > 0) {
            Box(Modifier.weight(appBytes / total).fillMaxHeight().background(appColor))
        }
        if (dataBytes > 0) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(separator))
            Box(Modifier.weight(dataBytes / total).fillMaxHeight().background(dataColor))
        }
        if (cacheBytes > 0) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(separator))
            val fraction = cacheBytes / total
            val width = if (barWidthPx > 0 && fraction * barWidthPx < minCachePx) {
                minCachePx / barWidthPx
            } else {
                fraction
            }
            Box(Modifier.weight(width).fillMaxHeight().background(cacheColor))
        }
    }
}

@Composable
private fun StorageLegend(appColor: Color, dataColor: Color, cacheColor: Color, content: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        LegendItem(appColor, stringResource(id = R.string.settings_storage_app_size), content)
        Spacer(Modifier.width(16.dp))
        LegendItem(dataColor, stringResource(id = R.string.settings_storage_user_data), content)
        Spacer(Modifier.width(16.dp))
        LegendItem(cacheColor, stringResource(id = R.string.settings_storage_cache), content)
    }
}

@Composable
private fun LegendItem(color: Color, text: String, content: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            color = content.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun StorageInfoRow(
    label: String,
    value: String,
    iconRes: Int,
    content: Color,
    isTotal: Boolean = false
) {
    Row(
        modifier = Modifier.height(32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(content),
            alpha = if (isTotal) 1f else 0.5f,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            fontSize = 16.sp,
            lineHeight = 16.sp,
            color = if (isTotal) content else content.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            text = value,
            fontSize = 16.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.End,
            color = content,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .weight(1f)
        )
    }
}
