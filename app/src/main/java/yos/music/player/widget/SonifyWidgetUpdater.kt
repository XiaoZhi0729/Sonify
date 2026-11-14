package yos.music.player.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import yos.music.player.MainActivity
import yos.music.player.R
import yos.music.player.code.NotificationPlaybackMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object SonifyWidgetUpdater {

    private data class Rendered4x2ModeState(
        val layoutRes: Int,
        val mode: NotificationPlaybackMode,
    )

    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val renderMutex = Mutex()
    private val renderEpoch = AtomicLong(0L)
    private val lastRendered4x2Mode = ConcurrentHashMap<Int, Rendered4x2ModeState>()

    @Volatile
    private var suppressFullUpdateUntilMs: Long = 0L

    enum class WidgetSize {
        SIZE_4X2,
        SIZE_4X1,
        SIZE_2X2,
    }

    fun updateAllWidgetsAsync(context: Context, forceEmpty: Boolean = false) {
        val appContext = context.applicationContext
        updateScope.launch {
            updateAllWidgets(appContext, forceEmpty = forceEmpty)
        }
    }

    suspend fun updateAllWidgets(context: Context, forceEmpty: Boolean = false) {
        val epoch = renderEpoch.incrementAndGet()
        val waitMs = suppressFullUpdateUntilMs - SystemClock.elapsedRealtime()
        if (waitMs > 0L) {
            delay(waitMs)
            if (epoch != renderEpoch.get()) return
        }
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val state = SonifyWidgetState.resolveCurrentState(appContext, forceEmpty = forceEmpty)
        renderMutex.withLock {
            if (epoch != renderEpoch.get()) return
            updateWidgetsWithState(appContext, manager, state)
        }
    }

    fun pushOptimisticModeState(context: Context, mode: NotificationPlaybackMode) {
        val epoch = renderEpoch.incrementAndGet()
        val appContext = context.applicationContext
        suppressFullUpdateUntilMs = SystemClock.elapsedRealtime() + 240L
        updateScope.launch {
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, SonifyWidget4x2Provider::class.java))
            if (ids.isEmpty()) return@launch
            val state = SonifyWidgetState.resolveCurrentState(appContext).copy(mode = mode)
            renderMutex.withLock {
                if (epoch != renderEpoch.get()) return@launch
                for (id in ids) {
                    val options = manager.getAppWidgetOptions(id)
                    val views = buildRemoteViews(appContext, WidgetSize.SIZE_4X2, id, options, state)
                    manager.updateAppWidget(id, views)
                }
                if (!state.active) {
                    @Suppress("DEPRECATION")
                    manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_queue_flipper)
                }
            }
        }
    }

    fun pushOptimisticPlayState(context: Context, isPlaying: Boolean) {
        val epoch = renderEpoch.incrementAndGet()
        val appContext = context.applicationContext
        updateScope.launch {
            val manager = AppWidgetManager.getInstance(appContext)
            val current = SonifyWidgetState.resolveCurrentState(appContext)
            val updated = current.copy(
                active = current.currentTrack != null,
                isPlaying = isPlaying,
            )
            renderMutex.withLock {
                if (epoch != renderEpoch.get()) return@launch
                updateWidgetsWithState(appContext, manager, updated)
            }
        }
    }

    fun updateSpecificWidgetsAsync(
        context: Context,
        size: WidgetSize,
        appWidgetIds: IntArray,
    ) {
        if (appWidgetIds.isEmpty()) return
        val appContext = context.applicationContext
        updateScope.launch {
            val manager = AppWidgetManager.getInstance(appContext)
            val state = SonifyWidgetState.resolveCurrentState(appContext)
            for (id in appWidgetIds) {
                val options = manager.getAppWidgetOptions(id)
                val views = buildRemoteViews(
                    context = appContext,
                    size = size,
                    appWidgetId = id,
                    options = options,
                    state = state,
                    forceSetModeChild = true,
                )
                manager.updateAppWidget(id, views)
            }
            if (size == WidgetSize.SIZE_4X2 && !state.active) {
                @Suppress("DEPRECATION")
                manager.notifyAppWidgetViewDataChanged(appWidgetIds, R.id.widget_queue_flipper)
            }
        }
    }

    private suspend fun updateWidgetsWithState(
        context: Context,
        manager: AppWidgetManager,
        state: SonifyWidgetUiState,
    ) {
        val pairs = listOf(
            WidgetSize.SIZE_4X2 to SonifyWidget4x2Provider::class.java,
            WidgetSize.SIZE_4X1 to SonifyWidget4x1Provider::class.java,
            WidgetSize.SIZE_2X2 to SonifyWidget2x2Provider::class.java,
        )
        for ((size, clazz) in pairs) {
            val ids = manager.getAppWidgetIds(ComponentName(context, clazz))
            if (ids.isEmpty()) continue
            for (id in ids) {
                val options = manager.getAppWidgetOptions(id)
                val views = buildRemoteViews(context, size, id, options, state)
                manager.updateAppWidget(id, views)
            }
            if (size == WidgetSize.SIZE_4X2 && !state.active) {
                @Suppress("DEPRECATION")
                manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_queue_flipper)
            }
        }
    }

    private suspend fun buildRemoteViews(
        context: Context,
        size: WidgetSize,
        appWidgetId: Int,
        options: Bundle,
        state: SonifyWidgetUiState,
        forceSetModeChild: Boolean = false,
    ): RemoteViews {
        val density = context.resources.displayMetrics.density
        @Suppress("DEPRECATION")
        val scaledDensity = context.resources.displayMetrics.scaledDensity

        val track = state.currentTrack
        val titleText = track?.title?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.widget_no_track_title)
        val artistText = track?.artist?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.widget_no_track_artist)

        if (!state.active || track == null) {
            return buildEmptyViews(context, size, appWidgetId, state, titleText, artistText, density)
        }

        val availableTextPx = estimateAvailableTextWidthPx(size, options, density)
        val (titleSp, artistSp) = when (size) {
            WidgetSize.SIZE_4X2 -> 20f to 15f
            WidgetSize.SIZE_4X1 -> 14f to 12f
            WidgetSize.SIZE_2X2 -> 14f to 12f
        }
        val focusTarget = MarqueeFocusPlanner.decide(
            title = titleText,
            artist = artistText,
            titleSp = titleSp,
            artistSp = artistSp,
            availableWidthPx = availableTextPx,
            scaledDensity = scaledDensity,
        )

        val layoutRes = selectActiveLayout(size, focusTarget)
        val views = RemoteViews(context.packageName, layoutRes)

        bindCoverArt(
            context = context,
            views = views,
            thumbUri = track.thumbUri,
            sizeDp = when (size) {
                WidgetSize.SIZE_4X2 -> 156f
                WidgetSize.SIZE_4X1 -> 52f
                WidgetSize.SIZE_2X2 -> 56f
            },
            radiusDp = 12f,
            density = density,
        )

        bindNativeMarqueeText(
            views = views,
            title = titleText,
            artist = artistText,
            focusTarget = focusTarget,
        )

        val openAppIntent = openAppPendingIntent(context)
        if (size == WidgetSize.SIZE_2X2) {
            views.setOnClickPendingIntent(R.id.widget_card, openAppIntent)
        } else {
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent)
        }
        views.setOnClickPendingIntent(
            R.id.widget_btn_play_pause,
            actionPendingIntent(context, SonifyWidgetActionReceiver.ACTION_PLAY_PAUSE, 101),
        )
        views.setOnClickPendingIntent(
            R.id.widget_btn_next,
            actionPendingIntent(context, SonifyWidgetActionReceiver.ACTION_NEXT, 102),
        )

        if (size == WidgetSize.SIZE_4X1) {
            views.setImageViewResource(
                R.id.widget_btn_play_pause,
                if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
        } else {
            views.setImageViewResource(
                R.id.widget_play_pause_icon,
                if (state.isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )
            views.setTextViewText(
                R.id.widget_play_pause_label,
                context.getString(if (state.isPlaying) R.string.widget_pause else R.string.widget_play),
            )
        }

        if (size == WidgetSize.SIZE_4X2) {
            views.setOnClickPendingIntent(
                R.id.widget_btn_mode,
                actionPendingIntent(context, SonifyWidgetActionReceiver.ACTION_MODE, 103),
            )
            val prev = lastRendered4x2Mode[appWidgetId]
            val current = Rendered4x2ModeState(layoutRes, state.mode)
            if (forceSetModeChild || prev == null || prev != current) {
                views.setDisplayedChild(R.id.widget_mode_flipper, state.mode.ordinal)
                lastRendered4x2Mode[appWidgetId] = current
            }
        }

        return views
    }

    private suspend fun buildEmptyViews(
        context: Context,
        size: WidgetSize,
        appWidgetId: Int,
        state: SonifyWidgetUiState,
        titleText: String,
        artistText: String,
        density: Float,
    ): RemoteViews {
        val layoutRes = when (size) {
            WidgetSize.SIZE_4X2 -> R.layout.widget_4x2_empty
            WidgetSize.SIZE_4X1 -> R.layout.widget_4x1_empty
            WidgetSize.SIZE_2X2 -> R.layout.widget_2x2_empty
        }
        val views = RemoteViews(context.packageName, layoutRes)
        val openAppIntent = openAppPendingIntent(context)
        if (size == WidgetSize.SIZE_2X2) {
            views.setOnClickPendingIntent(R.id.widget_card, openAppIntent)
        } else {
            views.setOnClickPendingIntent(R.id.widget_root, openAppIntent)
        }
        views.setOnClickPendingIntent(
            R.id.widget_btn_resume,
            actionPendingIntent(context, SonifyWidgetActionReceiver.ACTION_RESUME_EMPTY, 104),
        )

        if (size == WidgetSize.SIZE_4X2) {
            lastRendered4x2Mode.remove(appWidgetId)
            val hasQueue = state.queue.isNotEmpty()
            views.setViewVisibility(R.id.widget_queue_flipper, if (hasQueue) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_empty_fallback_hint, if (hasQueue) View.GONE else View.VISIBLE)

            val serviceIntent = Intent(context, SonifyQueueShelfService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            @Suppress("DEPRECATION")
            views.setRemoteAdapter(R.id.widget_queue_flipper, serviceIntent)
            views.setPendingIntentTemplate(
                R.id.widget_queue_flipper,
                shelfItemTemplatePendingIntent(context),
            )

            views.setOnClickPendingIntent(
                R.id.widget_btn_prev_page,
                shelfPagePendingIntent(
                    context,
                    SonifyWidget4x2Provider.ACTION_SHELF_PREV,
                    appWidgetId,
                    appWidgetId * 10 + 1,
                ),
            )
            views.setOnClickPendingIntent(
                R.id.widget_btn_next_page,
                shelfPagePendingIntent(
                    context,
                    SonifyWidget4x2Provider.ACTION_SHELF_NEXT,
                    appWidgetId,
                    appWidgetId * 10 + 2,
                ),
            )
            return views
        }

        // 4x1 & 2x2 空载：封面 + 标题/歌手 + Play
        val artDp = if (size == WidgetSize.SIZE_4X1) 52f else 56f
        bindCoverArt(
            context = context,
            views = views,
            thumbUri = state.currentTrack?.thumbUri,
            sizeDp = artDp,
            radiusDp = 12f,
            density = density,
        )
        views.setTextViewText(R.id.widget_title_static, titleText)
        views.setTextViewText(R.id.widget_artist_static, artistText)
        return views
    }

    private suspend fun bindCoverArt(
        context: Context,
        views: RemoteViews,
        thumbUri: String?,
        sizeDp: Float,
        radiusDp: Float,
        density: Float,
    ) {
        val sizePx = (sizeDp * density).toInt()
        val radiusPx = radiusDp * density
        val bmp = SonifyWidgetArtwork.loadRounded(context, thumbUri, sizePx, radiusPx)
        if (bmp != null) {
            views.setImageViewBitmap(R.id.widget_art, bmp)
        } else {
            views.setImageViewResource(R.id.widget_art, R.drawable.widget_art_placeholder)
        }
    }

    private fun bindNativeMarqueeText(
        views: RemoteViews,
        title: String,
        artist: String,
        focusTarget: MarqueeFocusTarget,
    ) {
        views.setTextViewText(R.id.widget_title_marquee, title)
        views.setTextViewText(R.id.widget_title_static, title)
        views.setTextViewText(R.id.widget_artist_marquee, artist)
        views.setTextViewText(R.id.widget_artist_static, artist)

        when (focusTarget) {
            MarqueeFocusTarget.NONE -> {
                views.setViewVisibility(R.id.widget_title_marquee, View.GONE)
                views.setViewVisibility(R.id.widget_title_static, View.VISIBLE)
                views.setViewVisibility(R.id.widget_artist_marquee, View.GONE)
                views.setViewVisibility(R.id.widget_artist_static, View.VISIBLE)
            }
            MarqueeFocusTarget.TITLE -> {
                views.setViewVisibility(R.id.widget_title_marquee, View.VISIBLE)
                views.setViewVisibility(R.id.widget_title_static, View.GONE)
                views.setViewVisibility(R.id.widget_artist_marquee, View.GONE)
                views.setViewVisibility(R.id.widget_artist_static, View.VISIBLE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    views.setBoolean(R.id.widget_title_marquee, "setFocusedByDefault", true)
                }
            }
            MarqueeFocusTarget.ARTIST -> {
                views.setViewVisibility(R.id.widget_title_marquee, View.GONE)
                views.setViewVisibility(R.id.widget_title_static, View.VISIBLE)
                views.setViewVisibility(R.id.widget_artist_marquee, View.VISIBLE)
                views.setViewVisibility(R.id.widget_artist_static, View.GONE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    views.setBoolean(R.id.widget_artist_marquee, "setFocusedByDefault", true)
                }
            }
        }
    }

    private fun selectActiveLayout(size: WidgetSize, focusTarget: MarqueeFocusTarget): Int =
        if (focusTarget == MarqueeFocusTarget.ARTIST) {
            when (size) {
                WidgetSize.SIZE_4X2 -> R.layout.widget_4x2_active_artist_focus
                WidgetSize.SIZE_4X1 -> R.layout.widget_4x1_active_artist_focus
                WidgetSize.SIZE_2X2 -> R.layout.widget_2x2_active_artist_focus
            }
        } else {
            when (size) {
                WidgetSize.SIZE_4X2 -> R.layout.widget_4x2_active
                WidgetSize.SIZE_4X1 -> R.layout.widget_4x1_active
                WidgetSize.SIZE_2X2 -> R.layout.widget_2x2_active
            }
        }

    private fun estimateAvailableTextWidthPx(
        size: WidgetSize,
        options: Bundle,
        density: Float,
    ): Int {
        val minWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val maxWidthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)
        val totalDp = if (size == WidgetSize.SIZE_2X2) {
            164f
        } else if (minWidthDp > 0) {
            minWidthDp.toFloat()
        } else {
            when (size) {
                WidgetSize.SIZE_4X2 -> 348f
                WidgetSize.SIZE_4X1 -> 256f
                WidgetSize.SIZE_2X2 -> 164f
            }
        }
        val reservedDp = when (size) {
            WidgetSize.SIZE_4X2 -> 14f * 2 + 156f + 14f
            WidgetSize.SIZE_4X1 -> 14f * 2 + 52f + 14f * 2 + 32f * 2 + 8f
            WidgetSize.SIZE_2X2 -> 14f * 2 + 4f
        }
        val availPx = ((totalDp - reservedDp).coerceAtLeast(60f) * density).toInt()
        Log.d(
            "SonifyWidget",
            "estimateWidth size=$size minW=${minWidthDp}dp maxW=${maxWidthDp}dp totalDp=$totalDp reservedDp=$reservedDp availPx=$availPx",
        )
        return availPx
    }

    private fun openAppPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            100,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun actionPendingIntent(
        context: Context,
        action: String,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, SonifyWidgetActionReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun shelfItemTemplatePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SonifyWidgetActionReceiver::class.java).apply {
            action = SonifyWidgetActionReceiver.ACTION_PLAY_RECENT
        }
        val mutableFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        return PendingIntent.getBroadcast(
            context,
            105,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag,
        )
    }

    private fun shelfPagePendingIntent(
        context: Context,
        action: String,
        appWidgetId: Int,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, SonifyWidget4x2Provider::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
