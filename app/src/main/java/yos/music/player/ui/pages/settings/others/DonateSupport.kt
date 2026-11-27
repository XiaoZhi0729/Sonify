package yos.music.player.ui.pages.settings.others

import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.kyant.capsule.ContinuousRoundedRectangle
import io.github.vinceglb.confettikit.compose.ConfettiKit
import io.github.vinceglb.confettikit.core.Angle
import io.github.vinceglb.confettikit.core.Party
import io.github.vinceglb.confettikit.core.Position
import io.github.vinceglb.confettikit.core.emitter.Emitter
import kotlin.math.abs
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.donate.AggregatedDonor
import yos.music.player.data.donate.DonateLeaderboard
import yos.music.player.data.donate.DonateRepository
import yos.music.player.data.donate.DonorRecord
import yos.music.player.ui.navigation.popBackStackSafely
import yos.music.player.ui.pages.settings.Divider
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.theme.AppAccentColors
import yos.music.player.ui.theme.headline
import yos.music.player.ui.theme.headlineDark
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.SkeletonShimmer
import yos.music.player.ui.widgets.basic.SonifyDialog
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.TitleBarIcon
import yos.music.player.ui.widgets.basic.rememberSkeletonShimmer
import yos.music.player.ui.widgets.basic.skeleton
import yos.music.player.ui.widgets.effects.CoverArtworkState
import yos.music.player.ui.widgets.effects.coverArtworkEffect
import yos.music.player.ui.widgets.effects.coverArtworkGesture
import yos.music.player.ui.widgets.effects.rememberCoverArtworkState
import yos.music.player.ui.widgets.mochi.MochiButton
import yos.music.player.ui.widgets.mochi.MochiButtonDefaults
import yos.music.player.ui.widgets.mochi.MochiButtonStyle
import yos.music.player.ui.widgets.mochi.MochiSheet
import yos.music.player.ui.widgets.mochi.MochiSheetCloseButton
import yos.music.player.ui.widgets.mochi.MochiSheetContent
import yos.music.player.ui.widgets.mochi.MochiSheetDetent
import yos.music.player.ui.widgets.mochi.MochiSheetHeader
import yos.music.player.ui.widgets.mochi.rememberMochiSheetState

private enum class SyncBadgeKind { SYNCED, CACHED, SYNCING, ERROR }

private data class DonateUiState(
    val records: List<DonorRecord>? = null,
    val updatedAtMs: Long = 0L,
    val isLoading: Boolean = true,
    val isManualRefreshing: Boolean = false,
    val syncKind: SyncBadgeKind = SyncBadgeKind.SYNCING,
    val hasError: Boolean = false
)

private val AppleAvatarPalettes = listOf(
    Color(0xFFFF6961) to Color(0xFFE0483E),
    Color(0xFFFF9F40) to Color(0xFFE57A1F),
    Color(0xFF34C759) to Color(0xFF249F44),
    Color(0xFF32ADE6) to Color(0xFF1C85BA),
    Color(0xFF5856D6) to Color(0xFF4240B5),
    Color(0xFFAF52DE) to Color(0xFF8C37B8),
    Color(0xFFFF375F) to Color(0xFFD92347),
    Color(0xFF5AC8FA) to Color(0xFF2FA4DC)
)

private const val QrConfettiTapCount = 10

@Composable
fun DonateSupport(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val isDark = isFlamingoInDarkMode()
    val shimmer = rememberSkeletonShimmer()

    var uiState by remember { mutableStateOf(DonateUiState()) }
    val donateSheetState = rememberMochiSheetState(
        detents = listOf(MochiSheetDetent.Medium),
        initial = MochiSheetDetent.Medium,
        fitContent = true
    )
    val pageQrFx = rememberCoverArtworkState(feedback = { Vibrator.click(context) })
    val sheetQrFx = rememberCoverArtworkState(feedback = { Vibrator.click(context) })
    var qrTapCount by remember { mutableIntStateOf(0) }
    var confettiBurstKey by remember { mutableIntStateOf(0) }
    var sheetConfettiBurstKey by remember { mutableIntStateOf(0) }
    var selectedPodiumDonor by remember { mutableStateOf<Pair<Int, AggregatedDonor>?>(null) }

    val onPageQrTap: () -> Unit = {
        qrTapCount += 1
        if (qrTapCount >= QrConfettiTapCount) {
            qrTapCount = 0
            confettiBurstKey += 1
        }
    }

    val onSheetQrTap: () -> Unit = {
        qrTapCount += 1
        if (qrTapCount >= QrConfettiTapCount) {
            qrTapCount = 0
            sheetConfettiBurstKey += 1
        }
    }

    val anonymousLabel = stringResource(id = R.string.donate_anonymous)
    val initialFallback = stringResource(id = R.string.donate_initial_fallback)

    val leaderboard = remember(uiState.records, anonymousLabel) {
        uiState.records?.let { DonateRepository.buildLeaderboard(it, anonymousLabel) }
    }

    fun triggerRefresh(manual: Boolean) {
        if (uiState.isManualRefreshing) return
        scope.launch {
            uiState = uiState.copy(
                isManualRefreshing = manual,
                isLoading = uiState.records == null,
                syncKind = SyncBadgeKind.SYNCING,
                hasError = false
            )
            val result = DonateRepository.fetchAndCache()
            result.onSuccess { cached ->
                uiState = uiState.copy(
                    records = cached.records,
                    updatedAtMs = cached.updatedAtMs,
                    isLoading = false,
                    isManualRefreshing = false,
                    syncKind = SyncBadgeKind.SYNCED,
                    hasError = false
                )
            }.onFailure {
                val hasCached = uiState.records != null
                uiState = uiState.copy(
                    isLoading = false,
                    isManualRefreshing = false,
                    syncKind = if (hasCached) SyncBadgeKind.CACHED else SyncBadgeKind.ERROR,
                    hasError = !hasCached
                )
                if (manual) {
                    Toast.makeText(
                        context,
                        R.string.donate_toast_sync_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val cached = withContext(Dispatchers.IO) { DonateRepository.readCache() }
        if (cached != null) {
            val fresh = DonateRepository.isCacheFresh(cached.updatedAtMs)
            uiState = uiState.copy(
                records = cached.records,
                updatedAtMs = cached.updatedAtMs,
                isLoading = false,
                syncKind = if (fresh) SyncBadgeKind.SYNCED else SyncBadgeKind.CACHED
            )
            if (!fresh) {
                triggerRefresh(manual = false)
            }
        } else {
            triggerRefresh(manual = false)
        }
    }

    val saveQrAction: () -> Unit = {
        scope.launch {
            val saved = saveDonateQrToGallery(context)
            Toast.makeText(
                context,
                if (saved) R.string.donate_toast_saved else R.string.donate_toast_save_failed,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val openWechatAction: () -> Unit = {
        scope.launch {
            saveDonateQrToGallery(context)
            val opened = launchWeChatScanner(context)
            if (!opened) {
                Toast.makeText(
                    context,
                    R.string.donate_toast_wechat_not_found,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    SettingBackground {
        Title(
            title = stringResource(id = R.string.settings_others_donate),
            onBack = { navController.popBackStackSafely() },
            titleHorizontalPadding = 34.5.dp,
            listState = listState,
            onRefresh = { triggerRefresh(manual = true) },
            refreshing = uiState.isManualRefreshing,
            rightBarIcon = {
                TitleBarIcon(
                    icon = Icons.Rounded.Refresh,
                    onBack = { triggerRefresh(manual = true) }
                )
            }
        ) {
            item(key = "ticker") {
                DonateTickerBanner(
                    recent = leaderboard?.recent.orEmpty(),
                    anonymousLabel = anonymousLabel,
                    initialFallback = initialFallback,
                    isLoading = uiState.isLoading,
                    onClick = {
                        if (!leaderboard?.recent.isNullOrEmpty()) {
                            scope.launch {
                                listState.animateScrollToItem(index = 4)
                            }
                        }
                    }
                )
            }

            item(key = "hero") {
                Spacer(modifier = Modifier.height(12.dp))
                DonateHeroCard(
                    leaderboard = leaderboard,
                    isLoading = uiState.isLoading,
                    syncKind = uiState.syncKind,
                    updatedAtMs = uiState.updatedAtMs,
                    isDark = isDark,
                    onSupportClick = { donateSheetState.show() }
                )
            }

            item(key = "podium") {
                GroupSpacer()
                ListHeaderWithTrailing(
                    title = stringResource(id = R.string.donate_top3_header),
                    trailing = stringResource(id = R.string.donate_top3_subtitle)
                )
                when {
                    uiState.isLoading -> DonatePodiumSkeleton(shimmer = shimmer)
                    uiState.hasError && leaderboard == null -> DonateStateCard(
                        icon = Icons.Rounded.WifiOff,
                        title = stringResource(id = R.string.donate_error_title),
                        desc = stringResource(id = R.string.donate_error_desc),
                        actionText = stringResource(id = R.string.donate_retry),
                        onAction = { triggerRefresh(manual = true) }
                    )
                    leaderboard == null || leaderboard.top3.isEmpty() -> DonateStateCard(
                        icon = Icons.Rounded.FavoriteBorder,
                        title = stringResource(id = R.string.donate_empty_title),
                        desc = stringResource(id = R.string.donate_empty_desc),
                        actionText = null,
                        onAction = null
                    )
                    else -> DonatePodiumSection(
                        top3 = leaderboard.top3,
                        initialFallback = initialFallback,
                        isDark = isDark,
                        onDonorClick = { rank, donor -> selectedPodiumDonor = rank to donor },
                        onVacantClick = { donateSheetState.show() }
                    )
                }
            }

            if (uiState.isLoading || (leaderboard != null && leaderboard.recent.isNotEmpty())) {
                item(key = "recent_list") {
                    GroupSpacer()
                    val countText = leaderboard?.let {
                        stringResource(id = R.string.donate_recent_count, it.recordCount)
                    } ?: ""
                    ListHeaderWithTrailing(
                        title = stringResource(id = R.string.donate_recent_header),
                        trailing = countText
                    )
                    if (uiState.isLoading) {
                        DonateRecentSkeleton(shimmer = shimmer)
                    } else if (leaderboard != null) {
                        RecentDonationsSection(
                            records = leaderboard.recent,
                            top3RankByName = leaderboard.top3RankByName,
                            anonymousLabel = anonymousLabel,
                            initialFallback = initialFallback,
                            isDark = isDark
                        )
                    }
                }
            }

            item(key = "qr_gallery") {
                GroupSpacer()
                ListHeaderWithTrailing(
                    title = stringResource(id = R.string.donate_qr_section_header),
                    trailing = stringResource(id = R.string.donate_qr_section_subtitle)
                )
                DonateQrGalleryCard(
                    qrFx = pageQrFx,
                    onQrClick = onPageQrTap,
                    onSaveClick = saveQrAction,
                    onWeChatClick = openWechatAction
                )
            }

            item(key = "footer") {
                Text(
                    text = stringResource(id = R.string.donate_footer_note),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = (headline withNight headlineDark).copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 34.5.dp, vertical = 18.dp)
                )
            }
        }

        if (confettiBurstKey > 0) {
            key(confettiBurstKey) {
                LaunchedEffect(Unit) {
                    repeat(10) {
                        Vibrator.click(context)
                        delay(Random.nextLong(50, 100).milliseconds)
                    }
                    delay(5.seconds)
                    confettiBurstKey = 0
                }
                ConfettiKit(
                    modifier = Modifier.fillMaxSize(),
                    parties = donateParade()
                )
            }
        }
    }

    MochiSheet(
        state = donateSheetState,
        grabber = true
    ) {
        val sheetScrollState = rememberScrollState()
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                MochiSheetHeader(
                    title = stringResource(id = R.string.donate_sheet_title),
                    actions = {
                        MochiSheetCloseButton(
                            onClick = { donateSheetState.hide() },
                            contentDescription = stringResource(id = R.string.color_picker_close)
                        )
                    }
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(sheetScrollState, enabled = sheetScrollState.maxValue > 0)
                ) {
                    MochiSheetContent(
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        DonateQrBottomSheetContent(
                            qrFx = sheetQrFx,
                            onQrClick = onSheetQrTap,
                            onSaveClick = saveQrAction,
                            onWeChatClick = openWechatAction
                        )
                    }
                }
            }
            if (sheetConfettiBurstKey > 0) {
                key(sheetConfettiBurstKey) {
                    LaunchedEffect(Unit) {
                        repeat(10) {
                            Vibrator.click(context)
                            delay(Random.nextLong(50, 100).milliseconds)
                        }
                        delay(5.seconds)
                        sheetConfettiBurstKey = 0
                    }
                    ConfettiKit(
                        modifier = Modifier.matchParentSize(),
                        parties = donateParade()
                    )
                }
            }
        }
    }

    selectedPodiumDonor?.let { (rank, donor) ->
        val summary = stringResource(
            id = R.string.donate_dialog_summary,
            DonateRepository.formatAmount(donor.totalAmount),
            donor.count
        )
        val messageText = buildString {
            append(summary)
            append("\n")
            append(donor.latestDate)
            if (!donor.latestMessage.isNullOrBlank()) {
                append("\n\n“")
                append(donor.latestMessage)
                append("”")
            }
        }
        SonifyDialog(
            title = "No.$rank · ${donor.name}",
            message = messageText,
            positiveContent = stringResource(id = R.string.donate_dialog_ok),
            onPositive = { selectedPodiumDonor = null },
            onDismissRequest = { selectedPodiumDonor = null }
        )
    }
}

@Composable
private fun ListHeaderWithTrailing(title: String, trailing: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 34.5.dp, end = 34.5.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            lineHeight = 15.sp,
            color = headline withNight headlineDark
        )
        if (trailing.isNotEmpty()) {
            Text(
                text = trailing,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                color = (headline withNight headlineDark).copy(alpha = 0.82f)
            )
        }
    }
}

@Composable
private fun DonateTickerBanner(
    recent: List<DonorRecord>,
    anonymousLabel: String,
    initialFallback: String,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val isDark = isFlamingoInDarkMode()
    val tickerItems = remember(recent) { recent.take(6) }
    var activeIndex by remember(tickerItems) { mutableIntStateOf(0) }

    LaunchedEffect(tickerItems) {
        if (tickerItems.size <= 1) return@LaunchedEffect
        while (true) {
            delay(3600L)
            activeIndex = (activeIndex + 1) % tickerItems.size
        }
    }

    val current = tickerItems.getOrNull(activeIndex)
    val hairline = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF3C3C43).copy(alpha = 0.12f)
    val surface = MaterialTheme.colorScheme.onSecondary.copy(alpha = if (isDark) 0.85f else 0.92f)
    val primary = MaterialTheme.colorScheme.primary
    val textPrimary = MaterialTheme.colorScheme.onBackground
    val textSecondary = textPrimary.copy(alpha = 0.56f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(CircleShape)
            .background(surface)
            .border(0.5.dp, hairline, CircleShape)
            .clickable(enabled = !isLoading && current != null, onClick = onClick)
            .padding(start = 10.dp, end = 12.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val displayName = current?.name?.trim()?.ifBlank { anonymousLabel } ?: "S"
        val avatarGradient = if (current != null) paletteForName(displayName) else primary to primary
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(avatarGradient.first, avatarGradient.second))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (current != null) DonateRepository.extractInitial(displayName, initialFallback) else "S",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Box(modifier = Modifier.weight(1f)) {
            if (isLoading) {
                Text(
                    text = stringResource(id = R.string.donate_status_syncing),
                    fontSize = 12.8.sp,
                    color = textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (current == null) {
                Text(
                    text = stringResource(id = R.string.donate_ticker_empty),
                    fontSize = 12.8.sp,
                    color = textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                AnimatedContent(
                    targetState = activeIndex,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "donateTicker"
                ) { idx ->
                    val rec = tickerItems.getOrNull(idx) ?: current
                    val recName = rec.name.trim().ifBlank { anonymousLabel }
                    val recAmt = DonateRepository.formatAmount(rec.amount)
                    val recDate = DonateRepository.resolveDate(rec.date, rec.createdAt)
                    val recTemplate = stringResource(
                        id = R.string.donate_ticker_template,
                        "__NAME__",
                        "__AMT__",
                        recDate
                    )
                    val annotated = remember(recName, recAmt, recTemplate, textPrimary, textSecondary, primary) {
                        buildTickerAnnotatedString(
                            template = recTemplate,
                            name = recName,
                            amount = recAmt,
                            baseColor = textSecondary,
                            nameColor = textPrimary,
                            amountColor = primary
                        )
                    }
                    Text(
                        text = annotated,
                        fontSize = 12.8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFeatureSettings = "tnum")
                    )
                }
            }
        }

        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = textPrimary.copy(alpha = 0.36f),
            modifier = Modifier.size(16.dp)
        )
    }
}

private fun buildTickerAnnotatedString(
    template: String,
    name: String,
    amount: String,
    baseColor: Color,
    nameColor: Color,
    amountColor: Color
) = buildAnnotatedString {
    var cursor = 0
    while (cursor < template.length) {
        val nameIdx = template.indexOf("__NAME__", cursor)
        val amtIdx = template.indexOf("__AMT__", cursor)
        val nextIdx = listOf(nameIdx, amtIdx).filter { it >= 0 }.minOrNull()
        if (nextIdx == null) {
            withStyle(SpanStyle(color = baseColor)) {
                append(template.substring(cursor))
            }
            break
        }
        if (nextIdx > cursor) {
            withStyle(SpanStyle(color = baseColor)) {
                append(template.substring(cursor, nextIdx))
            }
        }
        if (nextIdx == nameIdx) {
            withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.SemiBold)) {
                append(name)
            }
            cursor = nextIdx + "__NAME__".length
        } else {
            withStyle(SpanStyle(color = amountColor, fontWeight = FontWeight.SemiBold)) {
                append(amount)
            }
            cursor = nextIdx + "__AMT__".length
        }
    }
}

@Composable
private fun DonateHeroCard(
    leaderboard: DonateLeaderboard?,
    isLoading: Boolean,
    syncKind: SyncBadgeKind,
    updatedAtMs: Long,
    isDark: Boolean,
    onSupportClick: () -> Unit
) {
    val accent = MaterialTheme.colorScheme.primary
    val cardBg = if (isDark) Color(0xFF161618) else Color.White
    val ink1 = if (isDark) Color(0xFFF5F5F7) else Color(0xFF1D1D1F)
    val ink2 = ink1.copy(alpha = 0.58f)
    val ink3 = ink1.copy(alpha = 0.38f)
    val hairline = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF3C3C43).copy(alpha = 0.12f)
    val bentoBg = if (isDark) Color.White.copy(alpha = 0.065f) else Color(0xFF787880).copy(alpha = 0.075f)
    val ctaBg = if (isDark) Color(0xFFF5F5F7) else Color(0xFF1D1D1F)
    val ctaText = if (isDark) Color(0xFF1D1D1F) else Color.White

    val cardShape = ContinuousRoundedRectangle(24.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(cardShape)
            .background(cardBg)
            .border(0.5.dp, hairline, cardShape)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = if (isDark) 0.22f else 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.18f, size.height * 0.05f),
                        radius = size.width * 0.72f
                    ),
                    radius = size.width * 0.72f,
                    center = Offset(size.width * 0.18f, size.height * 0.05f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF6491FF).copy(alpha = if (isDark) 0.14f else 0.11f), Color.Transparent),
                        center = Offset(size.width * 0.86f, size.height * 0.06f),
                        radius = size.width * 0.65f
                    ),
                    radius = size.width * 0.65f,
                    center = Offset(size.width * 0.86f, size.height * 0.06f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFFFFB35C).copy(alpha = if (isDark) 0.10f else 0.09f), Color.Transparent),
                        center = Offset(size.width * 0.52f, size.height * 0.16f),
                        radius = size.width * 0.50f
                    ),
                    radius = size.width * 0.50f,
                    center = Offset(size.width * 0.52f, size.height * 0.16f)
                )
            }
            .padding(20.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.donate_total_label),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = ink2
                )
                SyncStatusPill(kind = syncKind, updatedAtMs = updatedAtMs, textColor = ink2)
            }

            Spacer(modifier = Modifier.height(6.dp))

            val totalDisplay = when {
                isLoading -> "¥---"
                leaderboard != null -> DonateRepository.formatAmount(leaderboard.totalAmount)
                else -> "¥--"
            }
            Text(
                text = totalDisplay,
                fontSize = 42.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1.2).sp,
                color = ink1,
                style = TextStyle(fontFeatureSettings = "tnum")
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(id = R.string.donate_hero_tagline),
                fontSize = 14.sp,
                lineHeight = 21.sp,
                color = ink2
            )

            Spacer(modifier = Modifier.height(18.dp))

            val supportersVal = when {
                isLoading -> "--"
                leaderboard != null -> stringResource(id = R.string.donate_metric_supporters_value, leaderboard.supporterCount)
                else -> "--"
            }
            val highestVal = when {
                isLoading -> "--"
                leaderboard != null && leaderboard.top3.isNotEmpty() ->
                    DonateRepository.formatAmount(leaderboard.top3.first().totalAmount)
                leaderboard != null -> "¥0"
                else -> "--"
            }
            val highestSub = when {
                isLoading -> stringResource(id = R.string.donate_status_syncing)
                leaderboard != null && leaderboard.top3.isNotEmpty() -> leaderboard.top3.first().name
                else -> stringResource(id = R.string.donate_slot_vacant)
            }
            val countVal = when {
                isLoading -> "--"
                leaderboard != null -> stringResource(id = R.string.donate_metric_count_value, leaderboard.recordCount)
                else -> "--"
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clip(ContinuousRoundedRectangle(16.dp))
                    .background(bentoBg)
                    .padding(vertical = 13.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BentoMetricCell(
                    label = stringResource(id = R.string.donate_metric_supporters),
                    value = supportersVal,
                    sub = stringResource(id = R.string.donate_metric_supporters_sub),
                    ink1 = ink1,
                    ink2 = ink2,
                    ink3 = ink3
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = 4.dp)
                        .width(0.5.dp)
                        .background(hairline)
                )
                BentoMetricCell(
                    label = stringResource(id = R.string.donate_metric_highest),
                    value = highestVal,
                    sub = highestSub,
                    ink1 = ink1,
                    ink2 = ink2,
                    ink3 = ink3
                )
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = 4.dp)
                        .width(0.5.dp)
                        .background(hairline)
                )
                BentoMetricCell(
                    label = stringResource(id = R.string.donate_metric_count),
                    value = countVal,
                    sub = stringResource(id = R.string.donate_metric_count_sub),
                    ink1 = ink1,
                    ink2 = ink2,
                    ink3 = ink3
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val heroButtonColors = MochiButtonDefaults.colors(MochiButtonStyle.Filled).copy(
                container = ctaBg,
                content = ctaText,
                glow = if (isDark) Color.Black.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.28f)
            )
            MochiButton(
                onClick = onSupportClick,
                colors = heroButtonColors,
                minHeight = 46.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(id = R.string.donate_cta_support),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun RowScope.BentoMetricCell(
    label: String,
    value: String,
    sub: String,
    ink1: Color,
    ink2: Color,
    ink3: Color
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .padding(horizontal = 10.dp)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = ink2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = ink1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFeatureSettings = "tnum")
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = sub,
            fontSize = 11.sp,
            color = ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SyncStatusPill(
    kind: SyncBadgeKind,
    updatedAtMs: Long,
    textColor: Color
) {
    val dotColor = when (kind) {
        SyncBadgeKind.SYNCED -> Color(0xFF34C759)
        SyncBadgeKind.CACHED, SyncBadgeKind.SYNCING -> Color(0xFFFF9500)
        SyncBadgeKind.ERROR -> Color(0xFFFF3B30)
    }
    val label = when (kind) {
        SyncBadgeKind.SYNCED -> stringResource(id = R.string.donate_status_synced)
        SyncBadgeKind.SYNCING -> stringResource(id = R.string.donate_status_syncing)
        SyncBadgeKind.ERROR -> stringResource(id = R.string.donate_status_error)
        SyncBadgeKind.CACHED -> formatCachedAge(updatedAtMs)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
            color = textColor
        )
    }
}

@Composable
private fun formatCachedAge(updatedAtMs: Long): String {
    if (updatedAtMs <= 0L) return stringResource(id = R.string.donate_status_cached_just_now)
    val diffMs = (System.currentTimeMillis() - updatedAtMs).coerceAtLeast(0L)
    val minutes = (diffMs / 60_000L).toInt()
    val hours = (diffMs / 3_600_000L).toInt()
    val days = (diffMs / 86_400_000L).toInt()
    return when {
        minutes < 1 -> stringResource(id = R.string.donate_status_cached_just_now)
        hours < 1 -> stringResource(id = R.string.donate_status_cached_minutes, minutes)
        days < 1 -> stringResource(id = R.string.donate_status_cached_hours, hours)
        else -> stringResource(id = R.string.donate_status_cached_days, days)
    }
}

@Composable
private fun DonatePodiumSection(
    top3: List<AggregatedDonor>,
    initialFallback: String,
    isDark: Boolean,
    onDonorClick: (Int, AggregatedDonor) -> Unit,
    onVacantClick: () -> Unit
) {
    val slots = listOf(2, 1, 3)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (rank in slots) {
            val weight = if (rank == 1) 1.14f else 1f
            val donor = top3.getOrNull(rank - 1)
            if (donor != null) {
                PodiumDonorCard(
                    rank = rank,
                    donor = donor,
                    initialFallback = initialFallback,
                    isDark = isDark,
                    onClick = { onDonorClick(rank, donor) },
                    modifier = Modifier.weight(weight)
                )
            } else {
                PodiumGhostCard(
                    rank = rank,
                    isDark = isDark,
                    onClick = onVacantClick,
                    modifier = Modifier.weight(weight)
                )
            }
        }
    }
}

@Composable
private fun PodiumDonorCard(
    rank: Int,
    donor: AggregatedDonor,
    initialFallback: String,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardBg = MaterialTheme.colorScheme.onSecondary
    val ink1 = MaterialTheme.colorScheme.onBackground
    val ink2 = ink1.copy(alpha = 0.56f)
    val ink3 = ink1.copy(alpha = 0.36f)
    val corner = if (rank == 1) 22.dp else 20.dp
    val shape = ContinuousRoundedRectangle(corner)

    val minHeight = when (rank) {
        1 -> 204.dp
        2 -> 174.dp
        else -> 154.dp
    }
    val avatarSize = when (rank) {
        1 -> 54.dp
        2 -> 46.dp
        else -> 44.dp
    }
    val tintColor = when (rank) {
        1 -> Color(0xFFD4A449).copy(alpha = 0.15f)
        2 -> (if (isDark) Color(0xFFCBD5E1) else Color(0xFF94A3B8)).copy(alpha = if (isDark) 0.10f else 0.12f)
        else -> (if (isDark) Color(0xFFD69668) else Color(0xFFBC7E52)).copy(alpha = if (isDark) 0.10f else 0.12f)
    }
    val stepColor = when (rank) {
        1 -> Color(0xFFD4A449)
        2 -> Color(0xFF94A3B8)
        else -> Color(0xFFBC7E52)
    }
    val borderColor = when {
        rank == 1 -> Color(0xFFC99D4A).copy(alpha = if (isDark) 0.28f else 0.30f)
        isDark -> Color.White.copy(alpha = 0.10f)
        else -> Color(0xFF3C3C43).copy(alpha = 0.12f)
    }

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .clip(shape)
            .background(cardBg)
            .border(0.5.dp, borderColor, shape)
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(tintColor, Color.Transparent),
                        center = Offset(size.width * 0.5f, 0f),
                        radius = size.width * 0.9f
                    ),
                    radius = size.width * 0.9f,
                    center = Offset(size.width * 0.5f, 0f)
                )
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, stepColor.copy(alpha = 0.72f), Color.Transparent),
                        startX = 16.dp.toPx(),
                        endX = size.width - 16.dp.toPx()
                    ),
                    start = Offset(16.dp.toPx(), size.height - 1.25.dp.toPx()),
                    end = Offset(size.width - 16.dp.toPx(), size.height - 1.25.dp.toPx()),
                    strokeWidth = 2.5.dp.toPx()
                )
            }
            .clickable(onClick = onClick)
            .padding(
                start = if (rank == 1) 10.dp else 8.dp,
                end = if (rank == 1) 10.dp else 8.dp,
                top = if (rank == 1) 16.dp else 14.dp,
                bottom = if (rank == 1) 15.dp else 13.dp
            )
    ) {
        Text(
            text = rank.toString(),
            fontSize = 54.sp,
            fontWeight = FontWeight.ExtraBold,
            color = ink1.copy(alpha = 0.038f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 8.dp)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight - 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.padding(bottom = 6.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    InitialCircleAvatar(
                        name = donor.name,
                        initialFallback = initialFallback,
                        size = avatarSize,
                        fontSize = if (rank == 1) 19f else 16.5f
                    )
                    PodiumMedalBadge(
                        rank = rank,
                        size = if (rank == 1) 20.dp else 18.dp,
                        borderColor = cardBg,
                        modifier = Modifier.offset(y = 5.dp)
                    )
                }

                Spacer(modifier = Modifier.height(5.dp))

                Text(
                    text = donor.name,
                    fontSize = if (rank == 1) 14.5.sp else 13.5.sp,
                    lineHeight = 17.sp,
                    fontWeight = if (rank == 1) FontWeight.SemiBold else FontWeight.Medium,
                    color = ink1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                if (donor.count > 1) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(ink1.copy(alpha = 0.07f))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = stringResource(id = R.string.donate_repeat_count, donor.count),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = ink2
                        )
                    }
                }

                if (!donor.latestMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(5.dp))
                    Text(
                        text = donor.latestMessage,
                        fontSize = 11.sp,
                        lineHeight = 14.5.sp,
                        color = ink2,
                        maxLines = if (rank == 1) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 10.dp)
            ) {
                Text(
                    text = DonateRepository.formatAmount(donor.totalAmount),
                    fontSize = if (rank == 1) 17.sp else 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = ink1,
                    maxLines = 1,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = donor.latestDate,
                    fontSize = 10.5.sp,
                    color = ink3,
                    maxLines = 1,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
            }
        }
    }
}

@Composable
private fun PodiumGhostCard(
    rank: Int,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val corner = if (rank == 1) 22.dp else 20.dp
    val shape = ContinuousRoundedRectangle(corner)
    val minHeight = when (rank) {
        1 -> 204.dp
        2 -> 174.dp
        else -> 154.dp
    }
    val ink1 = MaterialTheme.colorScheme.onBackground
    val dashColor = if (isDark) Color.White.copy(alpha = 0.16f) else Color(0xFF3C3C43).copy(alpha = 0.16f)

    Box(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val radiusPx = corner.toPx()
            drawRoundRect(
                color = dashColor,
                cornerRadius = CornerRadius(radiusPx, radiusPx),
                style = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                )
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(ink1.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    tint = ink1.copy(alpha = 0.52f),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(id = R.string.donate_slot_vacant),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = ink1.copy(alpha = 0.55f)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(id = R.string.donate_slot_rank_label, rank),
                fontSize = 10.5.sp,
                color = ink1.copy(alpha = 0.36f)
            )
        }
    }
}

@Composable
private fun PodiumMedalBadge(
    rank: Int,
    size: Dp,
    borderColor: Color,
    modifier: Modifier = Modifier
) {
    val (colors, textColor) = when (rank) {
        1 -> listOf(Color(0xFFF5E0A3), Color(0xFFD4A449)) to Color(0xFF3D2804)
        2 -> listOf(Color(0xFFF1F4F8), Color(0xFFBCC6D4)) to Color(0xFF242B35)
        else -> listOf(Color(0xFFEFC9AB), Color(0xFFBC7E52)) to Color(0xFF3B1B07)
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(colors))
            .border(2.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = rank.toString(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = textColor,
            style = TextStyle(fontFeatureSettings = "tnum")
        )
    }
}

@Composable
private fun RecentDonationsSection(
    records: List<DonorRecord>,
    top3RankByName: Map<String, Int>,
    anonymousLabel: String,
    initialFallback: String,
    isDark: Boolean
) {
    RoundColumn {
        records.forEachIndexed { idx, record ->
            if (idx > 0) Divider()
            val displayName = record.name.trim().ifBlank { anonymousLabel }
            val topRank = top3RankByName[displayName]
            RecentDonationRow(
                name = displayName,
                topRank = topRank,
                message = record.message,
                amount = DonateRepository.formatAmount(record.amount),
                date = DonateRepository.resolveDate(record.date, record.createdAt),
                initialFallback = initialFallback,
                isDark = isDark
            )
        }
    }
}

@Composable
private fun RecentDonationRow(
    name: String,
    topRank: Int?,
    message: String?,
    amount: String,
    date: String,
    initialFallback: String,
    isDark: Boolean
) {
    val ink1 = MaterialTheme.colorScheme.onBackground
    val ink2 = ink1.copy(alpha = 0.55f)
    val hairline = if (isDark) Color.White.copy(alpha = 0.12f) else Color(0xFF3C3C43).copy(alpha = 0.12f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .padding(start = 16.dp, end = 18.5.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        InitialCircleAvatar(
            name = name,
            initialFallback = initialFallback,
            size = 42.dp,
            fontSize = 16.5f
        )

        Spacer(modifier = Modifier.width(13.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = ink1.copy(alpha = 0.94f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (topRank != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    val tagColor = if (topRank == 1) {
                        if (isDark) Color(0xFFEBC36E) else Color(0xFF9E6E14)
                    } else {
                        ink2
                    }
                    val tagBg = if (topRank == 1) {
                        Color(0xFFD4A449).copy(alpha = 0.12f)
                    } else {
                        ink1.copy(alpha = 0.06f)
                    }
                    val tagBorder = if (topRank == 1) {
                        Color(0xFFD4A449).copy(alpha = 0.30f)
                    } else {
                        hairline
                    }
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(tagBg)
                            .border(0.5.dp, tagBorder, CircleShape)
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "No.$topRank",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = tagColor
                        )
                    }
                }
            }

            if (!message.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.5.dp))
                Text(
                    text = message,
                    fontSize = 13.2.sp,
                    lineHeight = 17.sp,
                    color = ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = amount,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = ink1,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
            Spacer(modifier = Modifier.height(2.5.dp))
            Text(
                text = date,
                fontSize = 12.sp,
                lineHeight = 15.sp,
                color = ink2,
                style = TextStyle(fontFeatureSettings = "tnum")
            )
        }
    }
}

@Composable
private fun DonateQrArtworkBox(
    qrFx: CoverArtworkState,
    width: Dp,
    cornerRadius: Dp,
    onClick: () -> Unit
) {
    val qrCorner = remember(cornerRadius) { ContinuousRoundedRectangle(cornerRadius) }
    Box(
        modifier = Modifier
            .width(width)
            .aspectRatio(1f)
            .coverArtworkGesture(
                state = qrFx,
                enabled = { true },
                onClick = onClick
            )
            .coverArtworkEffect(
                state = qrFx,
                cornerRadius = cornerRadius,
                squareFace = false
            )
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                clip = true
                this.shape = qrCorner
            }
            .drawWithCache {
                val outline = qrCorner.createOutline(
                    size = size,
                    layoutDirection = LayoutDirection.Ltr,
                    density = this
                )
                onDrawWithContent {
                    drawContent()
                    drawOutline(
                        outline = outline,
                        color = Color.Gray.copy(alpha = 0.1f),
                        style = Stroke(width = 12f)
                    )
                    drawOutline(
                        outline = outline,
                        color = Color.Gray.copy(alpha = 0.5f),
                        style = Stroke(width = 12f),
                        blendMode = BlendMode.Overlay
                    )
                }
            }
    ) {
        Image(
            painter = painterResource(id = R.drawable.donate_wechat_qr),
            contentDescription = stringResource(id = R.string.donate_qr_section_subtitle),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun DonateQrGalleryCard(
    qrFx: CoverArtworkState,
    onQrClick: () -> Unit,
    onSaveClick: () -> Unit,
    onWeChatClick: () -> Unit
) {
    val ink1 = MaterialTheme.colorScheme.onBackground
    val ink2 = ink1.copy(alpha = 0.56f)

    RoundColumn(shape = ContinuousRoundedRectangle(24.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            DonateQrArtworkBox(
                qrFx = qrFx,
                width = 216.dp,
                cornerRadius = 16.dp,
                onClick = onQrClick
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(id = R.string.donate_blessing_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = ink1,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(id = R.string.donate_blessing_body),
                fontSize = 13.8.sp,
                lineHeight = 22.sp,
                color = ink2,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(18.dp))

            DonateActionButtonsRow(
                saveLabel = stringResource(id = R.string.donate_btn_save_qr),
                wechatLabel = stringResource(id = R.string.donate_btn_open_wechat),
                onSaveClick = onSaveClick,
                onWeChatClick = onWeChatClick
            )
        }
    }
}

@Composable
private fun DonateQrBottomSheetContent(
    qrFx: CoverArtworkState,
    onQrClick: () -> Unit,
    onSaveClick: () -> Unit,
    onWeChatClick: () -> Unit
) {
    val isDark = isFlamingoInDarkMode()
    val ink1 = MaterialTheme.colorScheme.onBackground
    val ink2 = ink1.copy(alpha = 0.56f)
    val hairline = if (isDark) Color.White.copy(alpha = 0.10f) else Color(0xFF3C3C43).copy(alpha = 0.12f)
    val cardShape = ContinuousRoundedRectangle(20.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(MaterialTheme.colorScheme.onSecondary)
            .border(0.5.dp, hairline, cardShape)
            .padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DonateQrArtworkBox(
            qrFx = qrFx,
            width = 196.dp,
            cornerRadius = 14.dp,
            onClick = onQrClick
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = stringResource(id = R.string.donate_sheet_desc),
            fontSize = 13.5.sp,
            lineHeight = 20.sp,
            color = ink2,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        DonateActionButtonsRow(
            saveLabel = stringResource(id = R.string.donate_btn_save_qr_short),
            wechatLabel = stringResource(id = R.string.donate_btn_open_wechat_short),
            onSaveClick = onSaveClick,
            onWeChatClick = onWeChatClick
        )
    }
}

@Composable
private fun DonateActionButtonsRow(
    saveLabel: String,
    wechatLabel: String,
    onSaveClick: () -> Unit,
    onWeChatClick: () -> Unit
) {
    val isDark = isFlamingoInDarkMode()
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = AppAccentColors.onPrimaryFor(primary)
    val primaryColors = MochiButtonDefaults.colors(MochiButtonStyle.Filled).copy(
        container = primary,
        content = onPrimary
    )
    val secondaryColors = MochiButtonDefaults.colors(MochiButtonStyle.Tonal).copy(
        container = MaterialTheme.colorScheme.onBackground.copy(alpha = if (isDark) 0.11f else 0.08f),
        content = MaterialTheme.colorScheme.onBackground
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MochiButton(
            onClick = onSaveClick,
            colors = primaryColors,
            minHeight = 46.dp,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = saveLabel,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        MochiButton(
            onClick = onWeChatClick,
            colors = secondaryColors,
            minHeight = 46.dp,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = wechatLabel,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun DonateStateCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    actionText: String?,
    onAction: (() -> Unit)?
) {
    val ink1 = MaterialTheme.colorScheme.onBackground
    val ink2 = ink1.copy(alpha = 0.56f)
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = AppAccentColors.onPrimaryFor(primary)
    val retryColors = MochiButtonDefaults.colors(MochiButtonStyle.Filled).copy(
        container = primary,
        content = onPrimary
    )

    RoundColumn {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(ink1.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = ink2,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                fontSize = 15.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = ink1,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = desc,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = ink2,
                textAlign = TextAlign.Center
            )
            if (actionText != null && onAction != null) {
                Spacer(modifier = Modifier.height(14.dp))
                MochiButton(
                    onClick = onAction,
                    colors = retryColors,
                    minHeight = 42.dp,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = actionText,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun DonatePodiumSkeleton(shimmer: SkeletonShimmer) {
    val cardBg = MaterialTheme.colorScheme.onSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        listOf(2, 1, 3).forEach { rank ->
            val minHeight = when (rank) {
                1 -> 204.dp
                2 -> 174.dp
                else -> 154.dp
            }
            val avatarSize = if (rank == 1) 54.dp else 44.dp
            val corner = if (rank == 1) 22.dp else 20.dp
            Column(
                modifier = Modifier
                    .weight(if (rank == 1) 1.14f else 1f)
                    .defaultMinSize(minHeight = minHeight)
                    .clip(ContinuousRoundedRectangle(corner))
                    .background(cardBg)
                    .padding(horizontal = 10.dp, vertical = 15.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(avatarSize)
                            .skeleton(shimmer, cornerRadius = avatarSize / 2)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.65f)
                            .height(13.dp)
                            .skeleton(shimmer)
                    )
                    Spacer(modifier = Modifier.height(7.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.82f)
                            .height(10.dp)
                            .skeleton(shimmer)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .width(52.dp)
                            .height(15.dp)
                            .skeleton(shimmer)
                    )
                    Spacer(modifier = Modifier.height(5.dp))
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(10.dp)
                            .skeleton(shimmer)
                    )
                }
            }
        }
    }
}

@Composable
private fun DonateRecentSkeleton(shimmer: SkeletonShimmer) {
    RoundColumn {
        repeat(4) { idx ->
            if (idx > 0) Divider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 64.dp)
                    .padding(start = 16.dp, end = 18.5.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .skeleton(shimmer, cornerRadius = 21.dp)
                )
                Spacer(modifier = Modifier.width(13.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (idx % 2 == 0) 0.44f else 0.56f)
                            .height(15.dp)
                            .skeleton(shimmer)
                    )
                    Spacer(modifier = Modifier.height(7.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (idx % 2 == 0) 0.74f else 0.60f)
                            .height(12.dp)
                            .skeleton(shimmer)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(15.dp)
                            .skeleton(shimmer)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .width(62.dp)
                            .height(11.dp)
                            .skeleton(shimmer)
                    )
                }
            }
        }
    }
}

@Composable
private fun InitialCircleAvatar(
    name: String,
    initialFallback: String,
    size: Dp,
    fontSize: Float
) {
    val palette = remember(name) { paletteForName(name) }
    val initial = remember(name, initialFallback) { DonateRepository.extractInitial(name, initialFallback) }
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(palette.first, palette.second)))
            .border(0.5.dp, Color.Gray.copy(alpha = 0.22f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}

private fun paletteForName(name: String): Pair<Color, Color> {
    val trimmed = name.trim()
    var hash = 0
    for (ch in trimmed) {
        hash = ((hash shl 5) - hash) + ch.code
    }
    return AppleAvatarPalettes[abs(hash) % AppleAvatarPalettes.size]
}

private suspend fun saveDonateQrToGallery(context: Context): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val fileName = "Sonify_XiaoZhi_Donate_QR.jpg"
        val bytes = context.resources.openRawResource(R.drawable.donate_wechat_qr).use { it.readBytes() }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Sonify")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                } ?: throw IllegalStateException("Cannot open gallery output stream")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (err: Exception) {
                resolver.delete(uri, null, null)
                throw err
            }
        } else {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: return@runCatching false
            @Suppress("DEPRECATION")
            val inserted = MediaStore.Images.Media.insertImage(
                resolver,
                bitmap,
                fileName,
                "Sonify Donate QR"
            )
            bitmap.recycle()
            inserted != null
        }
    }.getOrDefault(false)
}

private fun launchWeChatScanner(context: Context): Boolean {
    val scanIntent = Intent().apply {
        component = ComponentName("com.tencent.mm", "com.tencent.mm.ui.LauncherUI")
        putExtra("LauncherUI.From.Scaner.Shortcut", true)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val launchedScanner = runCatching {
        context.startActivity(scanIntent)
        true
    }.getOrDefault(false)
    if (launchedScanner) return true

    val launchWeChat = context.packageManager.getLaunchIntentForPackage("com.tencent.mm")?.apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } ?: return false
    return runCatching {
        context.startActivity(launchWeChat)
        true
    }.getOrDefault(false)
}

private fun donateParade(): List<Party> {
    val party = Party(
        speed = 10f,
        maxSpeed = 30f,
        damping = 0.91f,
        angle = Angle.RIGHT - 45,
        spread = 270,
        colors = listOf(
            Color(0xFFFFB900).toArgb(),
            Color(0xFFC27AFF).toArgb(),
            Color(0xFFFF637E).toArgb(),
            Color(0xFFFB64B6).toArgb(),
            Color(0xFF51A2FF).toArgb(),
            Color(0xFFFF8904).toArgb(),
            Color(0xFF05DF72).toArgb()
        ),
        timeToLive = 4000L,
        emitter = Emitter(duration = 0.8.seconds).perSecond(150),
        position = Position.Relative(-0.1, 0.1)
    )

    return listOf(
        party,
        party.copy(
            angle = party.angle - 90,
            position = Position.Relative(1.1, 0.1)
        )
    )
}
