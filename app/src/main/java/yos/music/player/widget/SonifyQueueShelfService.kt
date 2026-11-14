package yos.music.player.widget

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import yos.music.player.R
import kotlin.math.ceil

class SonifyQueueShelfService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return ShelfFactory(applicationContext)
    }

    private class ShelfFactory(private val context: Context) : RemoteViewsFactory {
        private var queue: List<WidgetTrackSnapshot> = emptyList()

        override fun onCreate() {
            reload()
        }

        override fun onDataSetChanged() {
            reload()
        }

        override fun onDestroy() {
            queue = emptyList()
        }

        private fun reload() {
            queue = SonifyWidgetState.resolveCurrentState(context).queue
        }

        override fun getCount(): Int {
            if (queue.isEmpty()) return 0
            return ceil(queue.size / PAGE_SIZE.toDouble()).toInt()
        }

        override fun getViewAt(position: Int): RemoteViews {
            val page = RemoteViews(context.packageName, R.layout.widget_4x2_shelf_page)
            val density = context.resources.displayMetrics.density
            val artSizePx = (48f * density).toInt().coerceIn(64, 156)
            val radiusPx = 12f * density * (artSizePx / (70f * density))

            val base = position * PAGE_SIZE
            for (slot in 0 until PAGE_SIZE) {
                val idx = base + slot
                val ids = SLOT_IDS[slot]
                if (idx >= queue.size) {
                    page.setViewVisibility(ids.containerId, View.INVISIBLE)
                    continue
                }

                val item = queue[idx]
                page.setViewVisibility(ids.containerId, View.VISIBLE)
                page.setTextViewText(ids.titleId, item.title)
                page.setTextViewText(ids.artistId, item.artist)

                val bmp = SonifyWidgetArtwork.loadRoundedBlocking(
                    context = context,
                    thumbUri = item.thumbUri,
                    sizePx = artSizePx,
                    radiusPx = radiusPx,
                )
                if (bmp != null) {
                    page.setImageViewBitmap(ids.artId, bmp)
                } else {
                    page.setImageViewResource(ids.artId, R.drawable.widget_art_placeholder)
                }

                if (slot == 0) {
                    if (idx == 0) {
                        page.setViewVisibility(R.id.shelf_badge_last_0, View.VISIBLE)
                        page.setViewVisibility(R.id.shelf_badge_idx_0, View.GONE)
                    } else {
                        page.setViewVisibility(R.id.shelf_badge_last_0, View.GONE)
                        page.setViewVisibility(R.id.shelf_badge_idx_0, View.VISIBLE)
                        page.setTextViewText(R.id.shelf_badge_idx_0, (idx + 1).toString())
                    }
                } else {
                    page.setViewVisibility(ids.badgeIdxId, View.VISIBLE)
                    page.setTextViewText(ids.badgeIdxId, (idx + 1).toString())
                }

                val fillIn = Intent().apply {
                    putExtra(SonifyWidgetActionReceiver.EXTRA_TRACK_MEDIA_ID, item.mediaId)
                    putExtra(SonifyWidgetActionReceiver.EXTRA_TRACK_URI, item.uri)
                    putExtra(SonifyWidgetActionReceiver.EXTRA_TRACK_INDEX, idx)
                }
                page.setOnClickFillInIntent(ids.containerId, fillIn)
            }
            return page
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long = position.toLong()
        override fun hasStableIds(): Boolean = true
    }

    private data class SlotIds(
        val containerId: Int,
        val artId: Int,
        val titleId: Int,
        val artistId: Int,
        val badgeIdxId: Int,
    )

    companion object {
        private const val PAGE_SIZE = 4

        private val SLOT_IDS = arrayOf(
            SlotIds(
                R.id.shelf_slot_0,
                R.id.shelf_art_0,
                R.id.shelf_title_0,
                R.id.shelf_artist_0,
                R.id.shelf_badge_idx_0,
            ),
            SlotIds(
                R.id.shelf_slot_1,
                R.id.shelf_art_1,
                R.id.shelf_title_1,
                R.id.shelf_artist_1,
                R.id.shelf_badge_idx_1,
            ),
            SlotIds(
                R.id.shelf_slot_2,
                R.id.shelf_art_2,
                R.id.shelf_title_2,
                R.id.shelf_artist_2,
                R.id.shelf_badge_idx_2,
            ),
            SlotIds(
                R.id.shelf_slot_3,
                R.id.shelf_art_3,
                R.id.shelf_title_3,
                R.id.shelf_artist_3,
                R.id.shelf_badge_idx_3,
            ),
        )
    }
}
