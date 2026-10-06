package yos.music.player.data.libraries

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import com.funny.data_saver.core.mutableDataSaverStateOf
import yos.music.player.data.SettingsSaver

@Stable
object SettingsLibrary {

    /**
     * 是否显示音量条
     */
    @Stable
    var NowPlayingShowVolumeBar by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_show_volume_bar",
        initialValue = true
    )

    /**
     * 应用主题
     */
    @Stable
    var CustomTheme by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_theme",
        initialValue = "Auto"
    )

    /**
     * 是否已设置过屏幕圆角大小
     */
    @Stable
    var ScreenCornerSet by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_corner_set",
        initialValue = false
    )

    /**
     * 屏幕圆角大小
     */
    @Stable
    var ScreenCorner by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_corner",
        initialValue = "30"
    )

    /**
     * 歌曲排序
     */
    @Stable
    var SongSort by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_player_song_sort",
        initialValue = SongSortEnum.MUSIC_TITLE.ordinal
    )

    @Stable
    enum class SongSortEnum {
        MUSIC_TITLE, MUSIC_DURATION, ARTIST_NAME, MODIFIED_DATE
    }

    /**
     * 启用降序
     */
    @Stable
    var EnableDescending by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_player_enable_descending",
        initialValue = false
    )

    /**
     * 歌词界面 - 翻译
     */
    @Stable
    var NowPlayingTranslation by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "now_playing_translation",
        initialValue = true
    )

    /**
     * 资料库页是否显示本地音乐分区（关掉后只保留在线音乐相关内容，本地内容入口全部隐藏）
     */
    @Stable
    var ShowLocalLibrary by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_library_show_local_library",
        initialValue = true
    )

    /**
     * 每次启动时刷新媒体库
     */
    @Stable
    var RefreshEveryTime by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_library_refresh_everytime",
        initialValue = false
    )

    /**
     * 歌词字体字重
     */
    @Stable
    var LyricFontWeight by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_font_weight",
        initialValue = "ExtraBold"
    )

    /**
     * 歌词平衡行模式
     */
    @Stable
    var LyricLineBalance by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_line_balance",
        initialValue = false
    )

    /**
     * 歌词模糊效果
     */
    @Stable
    var LyricBlurEffect by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_blur_effect",
        initialValue = false
    )

    /**
     * 播放界面背景动态效果
     */
    @Stable
    var NowplayingBackgroundEffect by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_background_effect",
        initialValue = false
    )

    /**
     * 界面工具栏模糊效果
     */
    @Stable
    var BarBlurEffect by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_blur_effect",
        initialValue = false
    )

    /**
     * 媒体通知-额外的媒体图标
     */
    @Stable
    var NotificationEnableIcon by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_notification_enable_icon",
        initialValue = true
    )

    /**
     * 媒体通知-小一号图标
     */
    @Stable
    var NotificationSmallerIcon by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_notification_smaller_icon",
        initialValue = false
    )

    /**
     * 播放历史
     */
    @Stable
    var ListenHistory by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_play_history",
        initialValue = true
    )

    /**
     * 状态栏歌词
     */
    @Stable
    var StatusBarLyricEnabled by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "statusBarLyricEnabled",
        initialValue = false
    )

    /**
     * 状态栏歌词 Hook 状态
     */
    @Stable
    var StatusBarLyricHooked by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "statusBarLyricHooked",
        initialValue = false
    )

    /**
     * 超级岛歌词(LyricInfo 元数据,供 HyperLyric / ColorOS 锁屏歌词读取)
     */
    @Stable
    var SuperIslandLyricEnabled by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_extend_super_island_lyric",
        initialValue = true
    )

    /**
     * ExoPlayer行为 - 音频属性
     */
    @Stable
    var AudioAttributes by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_audio_exoplayer_audio_attributes",
        initialValue = true
    )

    /**
     * ExoPlayer解码 - 编解码器
     */
    @Stable
    var Codec by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_audio_exoplayer_codec",
        initialValue = "Auto"
    )

    /**
     * ExoPlayer解码 - 硬件音频轨道播放参数
     */
    @Stable
    var HardwareAudioTrackPlayBackParams by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_audio_exoplayer_hardware_audio_track_playback_params",
        initialValue = false
    )

    /**
     * ExoPlayer解码 - 音频浮点输出
     */
    @Stable
    var AudioFloatOutput by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_audio_exoplayer_audio_float_output",
        initialValue = false
    )

    /**
     * 在线音质 - WiFi 环境下请求的音质档位（128/320/flac/high）。
     *
     * setter 收窄：历史上的 `applyExplicitQuality` 会把 WiFi 与流量两档一并写死，
     * 用户在播放页只改"这一首"却永久抹掉了设置页的分设且不可撤销。
     * 现在只有 [updateOnlineQuality] 能写（设置页专用），其余入口只能走本曲覆盖。
     */
    @Stable
    var OnlineQualityWifi by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_audio_online_quality_wifi",
        initialValue = "128"
    )
        private set

    /**
     * 在线音质 - 流量环境下请求的音质档位（128/320/flac/high）；写入口同上。
     */
    @Stable
    var OnlineQualityMobile by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_audio_online_quality_mobile",
        initialValue = "128"
    )
        private set

    /** 在线音质偏好的唯一写入口。 */
    fun updateOnlineQuality(wifi: String? = null, mobile: String? = null) {
        if (wifi == null && mobile == null) return
        wifi?.let { OnlineQualityWifi = it }
        mobile?.let { OnlineQualityMobile = it }
    }

    /**
     * 在线音质 - 实际拿到低于请求档位时是否弹提示。
     *
     * 默认开启：诊断报告中"选了 Hi-Res 却默默退回无损、只听一声卡顿"是最掉信任的一种
     * 体验，不给任何告知说不过去。实现上只在verdict=DOWNGRADED 时发声（不把
     * 用户主动降档、沿用更高音源误报成故障），且10 分钟内同一首同一降级路径只弹一次。
     */
    @Stable
    var ShowQualityDowngradeToast by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_audio_online_quality_downgrade_toast",
        initialValue = true
    )

    /**
     * 排除一分钟以内的歌曲
     */
    @Stable
    var EnableExcludeSongsUnderOneMinute by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_library_enable_exclude_songs_under_one_minute",
        initialValue = true
    )

    /**
     * 倍速播放（0.25–4.0，1.0 为常速）；服务创建时应用，随设置持久化
     */
    @Stable
    var PlaybackSpeed by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_play_playback_speed",
        initialValue = 1.0
    )

    /**
     * 睡眠定时器到点行为：true=等当前曲自然播完再暂停；false=立即淡出暂停（默认，保持原行为）
     */
    @Stable
    var EnableFinishCurrentSongBeforePause by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_play_sleep_timer_finish_current_song",
        initialValue = false
    )

    /**
     * 歌曲平滑过渡（Crossfade）：自然播完自动进下一首时，用 [CrossfadeDuration] 让两首歌
     * 重叠过渡。默认关——它会多起一台播放器与一路解码器，属于要让用户自主取舍的音频行为，
     * 与 [EnableFinishCurrentSongBeforePause] 同样"默认保持原行为"。
     *
     * 手动切歌不受本开关影响，始终是硬切（判据在 CrossfadeExo，不在这里）。
     */
    @Stable
    var CrossfadeEnabled by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_play_crossfade_enabled",
        initialValue = false
    )

    /**
     * 平滑过渡时长（秒，字符串存值）：沿用 [Codec]、[ScreenCorner] 的"选项即字符串"惯例，
     * 使设置页的 SelectItem 可以直接拿它当选中值比对。可选档位与默认值在
     * [yos.music.player.code.utils.player.CrossfadePolicy]（引擎与设置页共用一张表）。
     */
    @Stable
    var CrossfadeDuration by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_play_crossfade_duration",
        initialValue = yos.music.player.code.utils.player.CrossfadePolicy.DEFAULT_DURATION_SEC.toString()
    )

    /**
     * 诊断日志落盘开关（[yos.music.player.code.utils.others.YosDiagnostics]）。
     *
     * 默认开：要抓的恰恰是"熄屏后无人旁观时"的无声事件——日志只在事发前就在写才有用。
     * 关掉只是不再产生新行，已落盘的历史仍可导出（否则自断取证）。成本：播放中 5s 一行、
     * 停播 60s 一行，单文件 2MB 轮转、只留最近 8 个，量级在几 MB 内。
     */
    @Stable
    var DiagLogEnabled by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_others_diag_log_enabled",
        initialValue = true
    )

    /**
     * 一键把全部设置恢复默认值。先清空 settings MMKV（顺带清掉历史遗留的无主 key），
     * 再把上面每个属性重新赋回声明处的 initialValue——赋值走 setter 会把默认值写回
     * MMKV，内存态即时生效，UI 无需重启即刷新。
     *
     * 登录凭证在独立的 kugou_auth MMKV 实例里，本函数不触碰。
     *
     * 注意：新增设置项时必须同步在此补一行赋值，否则该项不会被重置。
     */
    fun resetAllToDefaults() {
        SettingsSaver.clearAll()
        NowPlayingShowVolumeBar = true
        CustomTheme = "Auto"
        ScreenCornerSet = false
        ScreenCorner = "30"
        SongSort = SongSortEnum.MUSIC_TITLE.ordinal
        EnableDescending = false
        NowPlayingTranslation = true
        ShowLocalLibrary = true
        RefreshEveryTime = false
        LyricFontWeight = "ExtraBold"
        LyricLineBalance = false
        LyricBlurEffect = false
        NowplayingBackgroundEffect = false
        BarBlurEffect = false
        NotificationEnableIcon = true
        NotificationSmallerIcon = false
        ListenHistory = true
        StatusBarLyricEnabled = false
        StatusBarLyricHooked = false
        SuperIslandLyricEnabled = true
        AudioAttributes = true
        Codec = "Auto"
        HardwareAudioTrackPlayBackParams = false
        AudioFloatOutput = false
        OnlineQualityWifi = "128"
        OnlineQualityMobile = "128"
        ShowQualityDowngradeToast = true
        EnableExcludeSongsUnderOneMinute = true
        PlaybackSpeed = 1.0
        EnableFinishCurrentSongBeforePause = false
        CrossfadeEnabled = false
        CrossfadeDuration =
            yos.music.player.code.utils.player.CrossfadePolicy.DEFAULT_DURATION_SEC.toString()
        DiagLogEnabled = true
    }
}
