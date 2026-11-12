package yos.music.player.code

import androidx.media3.common.Player

/**
 * 媒体通知栏的「播放模式」四态。
 *
 * 通知栏只有两个自定义按钮位（媒体通知的动作用完 ⏮▶⏭ 后只剩两个），
 * 随机与循环各自一颗就占满了，收藏没有位置。所以把两者合并成**一颗**按钮，
 * 按国内播放器的通行做法走四态：顺序 → 列表循环 → 单曲循环 → 随机 → 顺序。
 *
 * 注意底层仍是 media3 的两路**独立**状态（shuffleModeEnabled / repeatMode），
 * 这里只定义「这两路状态怎么映射成一个显示态」以及「点一下推到哪个目标态」。
 * 应用内播放页的两颗独立按钮照旧各写各的状态，本文件的派生能正确跟上。
 */
enum class NotificationPlaybackMode {
    /** 顺序播放：不随机、不循环。 */
    ORDER,

    /** 列表循环：整队列循环，不随机。 */
    REPEAT_ALL,

    /** 单曲循环：当前曲循环，不随机。 */
    REPEAT_ONE,

    /** 随机播放：随机打散，不额外循环。 */
    SHUFFLE,
}

/**
 * 从播放器的两路独立状态派生出通知栏要显示的那一态。
 *
 * 随机优先：随机打开时队列本就被打散，再叠加循环语义在通知栏上无法用一个图标表达
 * （应用内两颗键允许「随机 + 单曲循环」并存，那种组合在通知栏显示为 [SHUFFLE]）。
 * 优先级顺序必须是「随机 > 单曲 > 列表 > 顺序」，与 [nextNotificationMode] 的
 * 推进顺序成对，改动时两边一起改，否则会出现「点 A 显示 B」。
 */
fun notificationModeOf(shuffleModeEnabled: Boolean, repeatMode: Int): NotificationPlaybackMode =
    when {
        shuffleModeEnabled -> NotificationPlaybackMode.SHUFFLE
        repeatMode == Player.REPEAT_MODE_ONE -> NotificationPlaybackMode.REPEAT_ONE
        repeatMode == Player.REPEAT_MODE_ALL -> NotificationPlaybackMode.REPEAT_ALL
        else -> NotificationPlaybackMode.ORDER
    }

/**
 * 点一下通知栏模式键之后应该进入的那一态。
 *
 * 只算目标态、不碰播放器：写入由调用方做（要同时落到 shuffle 与 repeat 两路），
 * 这样这段映射可以脱离播放器单独测。四个目标态与 [notificationModeOf] 的四个分支
 * 一一对应，连点四下必然回到起点。
 */
fun nextNotificationMode(shuffleModeEnabled: Boolean, repeatMode: Int): NotificationPlaybackMode =
    when (notificationModeOf(shuffleModeEnabled, repeatMode)) {
        NotificationPlaybackMode.ORDER -> NotificationPlaybackMode.REPEAT_ALL
        NotificationPlaybackMode.REPEAT_ALL -> NotificationPlaybackMode.REPEAT_ONE
        NotificationPlaybackMode.REPEAT_ONE -> NotificationPlaybackMode.SHUFFLE
        NotificationPlaybackMode.SHUFFLE -> NotificationPlaybackMode.ORDER
    }
