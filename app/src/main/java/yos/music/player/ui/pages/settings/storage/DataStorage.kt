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
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.data.objects.AppStorage
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.widgets.basic.OptionDialog
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title

/**
 * 设置 > 数据与储存。
 *
 * 视觉对齐 Cresto「Data & Storage」：顶部信息头卡 + 存储用量分段占比条/图例/信息行 +
 * 缓存与重置分区（破坏性操作走确认弹窗）；全部复用本项目的设置页组件
 * （SettingBackground / Title / RoundColumn / ListHeader / LabelItem / OptionDialog）。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DataStorage(navController: NavController) =
    SettingBackground {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val calculating = stringResource(id = R.string.settings_storage_calculating)
        val cacheCleared = stringResource(id = R.string.settings_storage_cache_cleared)
        var usage by remember { mutableStateOf<AppStorage.Usage?>(null) }
        var showClearConfirm by remember { mutableStateOf(false) }

        fun refreshUsage() {
            scope.launch {
                usage = withContext(Dispatchers.IO) { AppStorage.usage(context) }
            }
        }
        LaunchedEffect(Unit) { refreshUsage() }

        Title(
            title = stringResource(id = R.string.settings_storage_data_storage),
            onBack = { navController.popBackStack() },
            titleHorizontalPadding = 32.dp,
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
                                title = stringResource(id = R.string.settings_storage_clear_cache),
                                superLink = true
                            ) { showClearConfirm = true }
                        }

                        GroupSpacer()

                        if (showClearConfirm) {
                            OptionDialog(
                                icon = {},
                                title = stringResource(id = R.string.settings_storage_clear_cache),
                                subTitle = stringResource(id = R.string.settings_storage_clear_cache_confirm),
                                content = null,
                                positiveContent = stringResource(id = R.string.common_ok),
                                negativeContent = stringResource(id = R.string.common_cancel),
                                destructive = true,
                                onPositive = {
                                    showClearConfirm = false
                                    scope.launch {
                                        withContext(Dispatchers.IO) { AppStorage.clearCaches(context) }
                                        refreshUsage()
                                        Toast.makeText(context, cacheCleared, Toast.LENGTH_SHORT).show()
                                    }
                                },
                                onNegative = { showClearConfirm = false },
                                onDismissRequest = { showClearConfirm = false }
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
