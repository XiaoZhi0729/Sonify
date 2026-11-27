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
    var NowPlayingShowVolumeBar by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_show_volume_bar",
        initialValue = true
    )

    /**
     * 是否显示播放页评论入口按钮
     */
    var NowPlayingShowCommentsEntry by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_show_comments_entry",
        initialValue = true
    )

    /**
     * 是否在进度条上标出高潮起点（在线歌曲，数据来自酷狗服务端预计算）。
     *
     * 关闭时不发 `/song/climax` 请求——不只是不画，还省掉一次网络往返。
     */
    var NowPlayingShowClimaxMark by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_show_climax_mark",
        initialValue = true
    )

    /**
     * 播放页自动隐藏状态栏：播放页展开期间隐藏系统状态栏，背景/封面铺满到屏幕顶端。
     *
     * **布局位置不变**——隐藏期间顶部 inset 由 RawWindowInsets 钉在隐藏前的实测值上
     * （见 code/systemui/AutoHideStatusBar.kt）。默认关闭：这是明显的界面风格改变，
     * 与「背景动态效果」「封面取色背景」同惯例，由用户主动选择。
     */
    var NowPlayingAutoHideStatusBar by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_auto_hide_status_bar",
        initialValue = false
    )

    /**
     * 应用主题
     */
    var CustomTheme by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_theme",
        initialValue = "Auto"
    )

    /**
     * 主题强调色（预设 ID 如 "red" / "blue"，或 "custom:#RRGGBB"）
     */
    var AccentColor by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_accent_color",
        initialValue = yos.music.player.ui.theme.AppAccentColors.DefaultId
    )

    /**
     * 最近使用过的自定义主题色（逗号分隔的 #RRGGBB 列表，最多 8 个）
     */
    var AccentColorRecents by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_accent_color_recents",
        initialValue = ""
    )

    /**
     * 是否已设置过屏幕圆角大小
     */
    var ScreenCornerSet by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_corner_set",
        initialValue = false
    )

    /**
     * 屏幕圆角大小
     */
    var ScreenCorner by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_corner",
        initialValue = "30"
    )

    /**
     * 歌曲排序
     */
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
    var EnableDescending by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_player_enable_descending",
        initialValue = false
    )

    @Stable
    enum class LibraryListSortEnum {
        DEFAULT, NAME, SONG_COUNT
    }

    var LocalAlbumsSort by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_local_albums_sort",
        initialValue = LibraryListSortEnum.NAME.ordinal
    )

    var LocalAlbumsDescending by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_local_albums_descending",
        initialValue = false
    )

    var CollectedAlbumsSort by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_collected_albums_sort",
        initialValue = LibraryListSortEnum.DEFAULT.ordinal
    )

    var CollectedAlbumsDescending by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_collected_albums_descending",
        initialValue = false
    )

    var LocalPlaylistsSort by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_local_playlists_sort",
        initialValue = LibraryListSortEnum.NAME.ordinal
    )

    var LocalPlaylistsDescending by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_local_playlists_descending",
        initialValue = false
    )

    var OnlinePlaylistsSort by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_online_playlists_sort",
        initialValue = LibraryListSortEnum.DEFAULT.ordinal
    )

    var OnlinePlaylistsDescending by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_online_playlists_descending",
        initialValue = false
    )

    var LocalArtistsSort by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_local_artists_sort",
        initialValue = LibraryListSortEnum.NAME.ordinal
    )

    var LocalArtistsDescending by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_local_artists_descending",
        initialValue = false
    )

    var OnlineArtistsSort by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_online_artists_sort",
        initialValue = LibraryListSortEnum.DEFAULT.ordinal
    )

    var OnlineArtistsDescending by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_online_artists_descending",
        initialValue = false
    )

    @Stable
    enum class PlaylistDetailSortEnum {
        DEFAULT, NAME, ARTIST
    }

    var OnlinePlaylistDetailSort by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_online_playlist_detail_sort",
        initialValue = PlaylistDetailSortEnum.DEFAULT.ordinal
    )

    var OnlinePlaylistDetailDescending by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_online_playlist_detail_descending",
        initialValue = false
    )

    @Stable
    enum class AlbumDetailSortEnum {
        DEFAULT, NAME
    }

    var AlbumDetailSort by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_album_detail_sort",
        initialValue = AlbumDetailSortEnum.DEFAULT.ordinal
    )

    var AlbumDetailDescending by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "yos_library_album_detail_descending",
        initialValue = false
    )

    /**
     * 歌词界面 - 翻译
     */
    var NowPlayingTranslation by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "now_playing_translation",
        initialValue = true
    )

    /**
     * 资料库页是否显示本地音乐分区（关掉后只保留在线音乐相关内容，本地内容入口全部隐藏）
     */
    var ShowLocalLibrary by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_library_show_local_library",
        initialValue = true
    )

    /**
     * 是否使用新版资料库布局（关闭时回退到经典列表布局）
     */
    var UseNewLibrary by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_library_use_new_library",
        initialValue = true
    )

    /**
     * 新版资料库中置顶的歌单 / 收藏专辑 / 本地播放列表标识（逗号分隔）
     */
    var LibraryPinnedIds by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_library_pinned_ids",
        initialValue = ""
    )

    /**
     * 每次启动时刷新媒体库
     */
    var RefreshEveryTime by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_library_refresh_everytime",
        initialValue = false
    )

    /**
     * 歌词字体字重（支持 100..900 无极调节，默认 "800"；向后兼容旧版 "Thin".."Black" 枚举名）
     */
    var LyricFontWeight by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_font_weight",
        initialValue = "800"
    )

    /**
     * 歌词平衡行模式
     */
    var LyricLineBalance by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_line_balance",
        initialValue = false
    )

    /**
     * 歌词对唱分栏对齐（开启：对唱歌曲自动左右分栏；关闭：全部左对齐）
     */
    var LyricDuetAlignment by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_duet_alignment",
        initialValue = true
    )

    /**
     * 歌词模糊效果
     */
    var LyricBlurEffect by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_blur_effect",
        initialValue = false
    )

    /**
     * HDR 高光：歌词层改用加色混合（BlendMode.Plus），在封面上产生泛光；
     * 设备支持时，正在唱的那一句还会用超过 SDR 白的亮度真 HDR 发光。
     */
    var LyricHdrEffect by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_hdr_effect",
        initialValue = false
    )

    /**
     * HDR 高光强度 0..1：用掉屏幕可用亮度余量的多少。
     * 只在 [LyricHdrEffect] 打开、且系统支持 HDR 时生效。
     */
    var LyricHdrStrength by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_hdr_strength",
        initialValue = 0.6f
    )

    /**
     * 通知栏歌词：把当前歌词行写进媒体会话的标题。
     *
     * 通知栏、锁屏、系统媒体控件与车机 AVRCP 读的都是同一条 MediaSession 的
     * MediaMetadata，没有专用歌词通道——改写 title/artist 会让四处一起变成歌词，
     * 与音频走不走蓝牙无关（早期只在蓝牙输出时生效，故 key 里留着 bluetooth）。
     * 默认开：这是本 App 通知栏呈现歌词的方式。
     */
    var NotificationLyricEnabled by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_bluetooth_lyric",
        initialValue = true
    )

    /**
     * 歌词字号缩放（对齐 Flamingo 新版，全局钳制 0.8~1.2）
     */
    var LyricFontScale by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_font_scale",
        initialValue = 1.0f
    )

    /**
     * 是否独立配置横屏歌词字体大小
     */
    var LyricLandscapeFontScaleIndependent by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_landscape_font_scale_independent",
        initialValue = false
    )

    /**
     * 横屏歌词字号缩放（0.8f..1.2f，仅在 [LyricLandscapeFontScaleIndependent] 为 true 时独立生效）
     */
    var LyricLandscapeFontScale by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_landscape_font_scale",
        initialValue = 1.0f
    )

    /**
     * 自定义歌词字体在应用私有目录下的本地文件路径（空字符串表示系统默认）
     */
    var LyricCustomFontPath by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_custom_font_path",
        initialValue = ""
    )

    /**
     * 自定义歌词字体的显示文件名（空字符串表示系统默认）
     */
    var LyricCustomFontName by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_custom_font_name",
        initialValue = ""
    )

    /**
     * 智能逐字歌词：非逐字歌词按字符数线性分摊行时长模拟逐字扫色
     */
    var SmartWordByWordLyric by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_smart_wbw_lyric",
        initialValue = false
    )

    /**
     * 歌词时间偏移（毫秒）：正值让歌词提前，负值让歌词延后
     */
    var LyricTimingOffset by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_lyric_timing_offset",
        initialValue = 0
    )

    /**
     * 播放界面背景动态效果
     */
    var NowplayingBackgroundEffect by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_background_effect",
        initialValue = false
    )

    /**
     * 背景动态效果采用哪一种样式。仅在 [NowplayingBackgroundEffect] 为 true 时生效。
     *
     * - [BACKGROUND_MODE_DRIFT]：单张糊图慢速推拉（Ken Burns），默认。
     * - [BACKGROUND_MODE_MESH]：Pear Wall 流体网格（三份封面副本反向慢旋 + 网格形变），
     *   需要 OpenGL ES 3.0，不支持的设备自动退回漂移样式。
     */
    var NowplayingBackgroundMode by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_background_mode",
        initialValue = BACKGROUND_MODE_DRIFT
    )

    /**
     * 流体网格背景的流动速度档位（0..4，默认 2）。
     *
     * 原版 Pear Wall 的三份封面副本转一圈要 120s/70s/90s，那个节奏是给「偶尔瞟一眼」的
     * 桌面壁纸定的，放在会盯着看的播放页上几乎等于静止。档位换算见
     * `PearMeshState.flowSpeedMultiplier`：0 = 流动 1×（原版）、1 = 标准 3×、2 = 强烈 5×。
     */
    var NowplayingBackgroundFlowSpeed by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        // key 带 v2：档位从 5 档改成 3 档，旧键里存的 2/3/4 映射会错位。
        key = "settings_performance_ui_nowplaying_background_flow_speed_v2",
        initialValue = 1
    )

    /**
     * 跟随音乐律动：随鼓点让封面组轻微放大（1.00 → 1.33 倍，经重模糊后表现为雾状色块呼吸）。
     * 默认开启——这是 Pear Wall 那套观感的核心之一。
     *
     * 关掉后 `ProbingAudioSink` 里的分析整段短路，不产生任何每帧开销。
     */
    var NowplayingBackgroundPulse by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_background_pulse",
        initialValue = true
    )

    /**
     * 律动强度：柔和 / 标准 / 强烈（0..2，默认 1 = 上游原值 0.33）。
     * 换算见 `PearMeshState.pulseIntensityFor`：0.22 / **0.33** / 0.45。
     */
    var NowplayingBackgroundPulseStrength by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        // key 带 v4：档位从 5 档收成 3 档，且默认基准变了，旧键值会错位。
        key = "settings_performance_ui_nowplaying_background_pulse_strength_v4",
        initialValue = 1
    )

    /**
     * 模糊强度：轻柔 / 标准 / 浓郁（0..2，默认 1）。
     *
     * 直接乘进 Kawase 的 sigma：`sigma = LYRICS_BLUR_SIGMA × renderScale × blurMultiplier`。
     * 换算见 `PearMeshState.blurMultiplierFor`：0.75 / **1.0** / 1.4（原版四档 0.75/1/1.4/2
     * 里去掉了最高的 2.0，那一档在播放页上会把颜色糊成一片）。
     */
    var NowplayingBackgroundBlurStrength by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_background_blur_strength",
        initialValue = 1
    )

    /**
     * 渲染质量：省电 / 平衡 / 流畅（0..2，默认 1）。
     *
     * 同时决定内部渲染分辨率与帧率上限，见 `PearMeshState.renderQualityScale/Fps`：
     * 省电 0.15×/24fps、平衡 0.25×/30fps（原版默认）、流畅 0.35×/60fps。
     *
     * ★ 注意 renderScale 同时乘进模糊 sigma，所以「省电」档画面会明显更糊 —— 这是原版同款行为。
     */
    var NowplayingBackgroundRenderQuality by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_background_render_quality",
        initialValue = 1
    )

    /**
     * 流体网格的竖屏网格样式（0..3，对应界面上的 1..4）。
     *
     * 默认 3：Pear Wall 的**设置层**默认就是 3（内部 index 0 起），
     * 而 `PearMeshState` 自带的 DefaultPortraitPresetIndex 是 2 —— 两者不一致是上游遗留，
     * 以设置层为准，因为运行时总是用设置值覆盖 State。
     */
    var NowplayingBackgroundPortraitPreset by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_background_portrait_preset",
        initialValue = 3
    )

    /**
     * 流体网格的横屏网格样式（0..4，对应界面上的 1..5）。默认 3，同上游设置层。
     */
    var NowplayingBackgroundLandscapePreset by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_background_landscape_preset",
        initialValue = 3
    )

    /**
     * 每次进入播放页时随机换一套网格样式。
     *
     * 原版默认开启，但那是**壁纸**语义（息屏再亮屏时换个样子）。播放页没有对应时机，
     * 固定一套反而更稳，所以默认关。
     */
    var NowplayingBackgroundRandomPreset by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_background_random_preset",
        initialValue = false
    )

    /**
     * 音乐画廊背景是否跟随「音乐播放页」的背景设置。
     *
     * 默认开启：自动同步「音乐播放页」的静态 / 慢速漂移 / 流体网格选择；
     * 关闭后使用 [GalleryBackgroundMode] 独立控制画廊背景。
     */
    var GalleryFollowPlaybackBackground by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_gallery_follow_playback_background",
        initialValue = true
    )

    /**
     * 音乐画廊独立背景样式（仅在 [GalleryFollowPlaybackBackground] 为 false 时生效）：
     * - [GALLERY_BG_STATIC]：静态模糊底图
     * - [GALLERY_BG_DRIFT]：慢速漂移（默认）
     * - [GALLERY_BG_MESH]：流体网格（需设备支持 GLES3）
     */
    var GalleryBackgroundMode by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_gallery_background_mode",
        initialValue = GALLERY_BG_DRIFT
    )

    /**
     * 封面取色背景：四个 Tab 首页的整页底色改为当前歌曲封面提取的柔和纵向渐变。
     *
     * 默认关闭——开启后整屏底色会随歌曲变化，属于明显的视觉风格改变，
     * 由用户主动选择（与「背景动态效果」同一惯例）。
     */
    var TrackBackdropGradient by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_track_backdrop_gradient",
        initialValue = false
    )

    /**
     * 全屏静态封面：封面铺满播放页作为背景（高清方图贴边裁切 + 同图放大模糊副本打底），
     * 且不再随播放/暂停缩放。键名与 Flamingo 同项逐字一致。
     */
    var NowplayingStaticFullScreenAlbum by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_static_fullscreen_album",
        initialValue = false
    )

    /**
     * 专辑动态封面：在线歌曲存在官方 MP4 动态封面时，在播放页大封面上静音循环播放。
     */
    var NowPlayingDynamicCover by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_dynamic_cover",
        initialValue = true
    )

    /**
     * 允许在移动网络下加载动态封面；关闭时仅 Wi-Fi 发起请求（已缓存视频仍可播放）。
     */
    var NowPlayingDynamicCoverMobile by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_nowplaying_dynamic_cover_mobile",
        initialValue = false
    )

    /**
     * 界面工具栏模糊效果
     */
    var BarBlurEffect by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_blur_effect",
        initialValue = false
    )

    /**
     * 玻璃模糊强度倍率（0f..2f，1f = 原样）。
     *
     * 只乘在**玻璃控件**的模糊半径上（顶栏圆钮 / 底栏容器 / 迷你播放器外壳 / 玻璃开关 /
     * 下拉面板 / 对话框），顶部渐进模糊等背景类效果不受影响 —— 见 GlassBlurStrength.kt。
     */
    var GlassBlurScale by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_glass_blur_scale",
        initialValue = 1.0f
    )

    /**
     * 混色模糊（磨砂）强度倍率（0f..2f，1f = 原样），与 [GlassBlurScale] 相互独立。
     */
    var FrostedBlurScale by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_ui_frosted_blur_scale",
        initialValue = 1.0f
    )

    /**
     * 媒体通知-额外的媒体图标
     */
    var NotificationEnableIcon by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_notification_enable_icon",
        initialValue = true
    )

    /**
     * 媒体通知-小一号图标
     */
    var NotificationSmallerIcon by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_performance_notification_smaller_icon",
        initialValue = false
    )

    /**
     * 播放历史
     */
    var ListenHistory by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_play_history",
        initialValue = true
    )

    /**
     * 上传听歌时长：登录酷狗后，把在线歌曲的收听时长心跳上报到酷狗账号
     * （/user/grade/info 按 diff_sec 记账），并在在线歌曲开播时上报播放历史
     * （/playhistory/upload）。关闭后两者都停。
     */
    var UploadListeningDuration by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_upload_listening_duration",
        initialValue = true
    )

    /**
     * 状态栏歌词
     */
    var StatusBarLyricEnabled by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "statusBarLyricEnabled",
        initialValue = false
    )

    /**
     * 状态栏歌词 Hook 状态
     */
    var StatusBarLyricHooked by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "statusBarLyricHooked",
        initialValue = false
    )

    /**
     * ExoPlayer行为 - 音频属性
     */
    var AudioAttributes by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_audio_exoplayer_audio_attributes",
        initialValue = true
    )

    /**
     * ExoPlayer解码 - 编解码器
     */
    var Codec by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_audio_exoplayer_codec",
        initialValue = "Auto"
    )

    /**
     * ExoPlayer解码 - 硬件音频轨道播放参数
     */
    var HardwareAudioTrackPlayBackParams by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_audio_exoplayer_hardware_audio_track_playback_params",
        initialValue = false
    )

    /**
     * 音频浮点输出。
     *
     * **已停用（2026-11-01）**：只保留键与读取，实际不再影响播放——[yos.music.player.code.YosRenderFactory]
     * 建 sink 时无条件关掉 float 分支。原因是 media3 1.4.0 的 float 分支会静默丢弃所有人声分离
     * 处理器，而本工程解码输出日常是 16-bit、该分支本就不触发：收益为零、触发即破坏。
     * 设置页对应开关也已停用。留着这个字段只为旧安装的已存值能被安全读回，不做迁移。
     */
    var AudioFloatOutput by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_audio_exoplayer_audio_float_output",
        initialValue = false
    )

    /**
     * 人声分离默认占比（0..1，**不是 0..100**）。
     *
     * 0 = 纯伴奏（没人声），1 = 原声（人声伴奏都在，等同关闭）。用 0..1 是因为混音
     * 直接吃这个值做线性权重（wet = accomp·(1-r) + dry·r），界面上的百分比是另一套单位，
     * 混用会得到 100 倍偏差。
     *
     * 默认 0.5 而不是 1：1 在新语义下等于"没开"，会让点开按钮看起来毫无反应。
     */
    var VocalSeparationDefaultRatio by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_audio_vocal_separation_default_ratio",
        initialValue = 0.5f
    )

    /**
     * 在线音质 - WiFi 环境下请求的音质档位（128/320/flac/high）。
     *
     * 默认 **Hi-Res**：省流量的前提是"在家连 WiFi 时已经缓存了高音质"，而这个缓存只有在
     * WiFi 档本身够高时才会产生。默认 128K 会让整条"出门口直接放本地 Hi-Res"的链路
     * 永远没有素材可用（见 [yos.music.player.data.repositories.CachedQualityPreference]）。
     *
     * setter 收窄：历史上的 `applyExplicitQuality` 会把 WiFi 与流量两档一并写死，
     * 用户在播放页只改"这一首"却永久抹掉了设置页的分设且不可撤销。
     * 现在只有 [updateOnlineQuality] 能写（设置页专用），其余入口只能走本曲覆盖。
     */
    var OnlineQualityWifi by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_audio_online_quality_wifi",
        initialValue = "high"
    )
        private set

    /**
     * 在线音质 - 流量环境下请求的音质档位（128/320/flac/high）；写入口同上。
     */
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
    var ShowQualityDowngradeToast by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_audio_online_quality_downgrade_toast",
        initialValue = true
    )

    /**
     * 排除一分钟以内的歌曲
     */
    var EnableExcludeSongsUnderOneMinute by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_library_enable_exclude_songs_under_one_minute",
        initialValue = true
    )

    /**
     * 倍速播放（0.25–4.0，1.0 为常速）；服务创建时应用，随设置持久化
     */
    var PlaybackSpeed by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_play_playback_speed",
        initialValue = 1.0
    )

    /**
     * 睡眠定时器到点行为：true=等当前曲自然播完再暂停；false=立即淡出暂停（默认，保持原行为）
     */
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
    var CrossfadeDuration by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_play_crossfade_duration",
        initialValue = yos.music.player.code.utils.player.CrossfadePolicy.DEFAULT_DURATION_SEC.toString()
    )

    /**
     * 歌曲过渡模式：
     * - [CROSSFADE_MODE_SMOOTH]：经典等功率平滑过渡（按 [CrossfadeDuration] 固定秒数淡入淡出）
     * - [CROSSFADE_MODE_SMART]：LiveSort 智能无感过渡（首尾特征分析 + 自适应切入时机 + 呼吸留白 + 动态响度平衡 + 下潜滤波 EQ + 空间混响）
     */
    var CrossfadeMode by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_play_crossfade_mode",
        initialValue = CROSSFADE_MODE_SMOOTH
    )

    fun isSmartCrossfade(): Boolean = CrossfadeMode == CROSSFADE_MODE_SMART

    /**
     * 智能过渡的「音色与空间混响强度」：0 = 柔和，1 = 标准（默认），2 = 浓郁。
     */
    var SmartTransitionFxIntensity by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_play_smart_transition_fx_intensity",
        initialValue = 1
    )

    /**
     * 诊断日志落盘开关（[yos.music.player.code.utils.others.YosDiagnostics]）。
     *
     * 默认开：要抓的恰恰是"熄屏后无人旁观时"的无声事件——日志只在事发前就在写才有用。
     * 关掉只是不再产生新行，已落盘的历史仍可导出（否则自断取证）。成本：播放中 5s 一行、
     * 停播 60s 一行，单文件 2MB 轮转、只留最近 8 个，量级在几 MB 内。
     */
    var DiagLogEnabled by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_others_diag_log_enabled",
        initialValue = true
    )

    /**
     * ColorOS 锁屏歌词全链路诊断开关（事件走 [yos.music.player.code.utils.others.YosDiagnostics]，
     * 但独立于 [DiagLogEnabled] 生效：开着它即整体落盘，供外部用户排查"锁屏不出歌词"）。
     * 默认关：只有反馈歌词问题的用户才需要打开。
     */
    var ColorOsLyricDiagEnabled by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_others_coloros_lyric_diag_enabled",
        initialValue = false
    )

    /**
     * 音频字节缓存上限（MB）。在 SimpleCache 首次构造时读取，故改动于下次启动生效
     * （LRU 初始化时会 trim 到新上限）。默认 1024MB（原硬编码值）。
     */
    var AudioCacheCapMb by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_storage_audio_cache_cap_mb",
        initialValue = 1024
    )

    /**
     * 图片磁盘缓存上限（MB）。Coil 的 DiskCache.maxSize 只读，只能在 newImageLoader()
     * 建缓存时定，故改动于下次启动生效。默认 512MB（原硬编码值）。
     */
    var ImageCacheCapMb by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_storage_image_cache_cap_mb",
        initialValue = 512
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
        NowPlayingShowCommentsEntry = true
        CustomTheme = "Auto"
        AccentColor = yos.music.player.ui.theme.AppAccentColors.DefaultId
        AccentColorRecents = ""
        ScreenCornerSet = false
        ScreenCorner = "30"
        SongSort = SongSortEnum.MUSIC_TITLE.ordinal
        EnableDescending = false
        LocalAlbumsSort = LibraryListSortEnum.NAME.ordinal
        LocalAlbumsDescending = false
        CollectedAlbumsSort = LibraryListSortEnum.DEFAULT.ordinal
        CollectedAlbumsDescending = false
        LocalPlaylistsSort = LibraryListSortEnum.NAME.ordinal
        LocalPlaylistsDescending = false
        OnlinePlaylistsSort = LibraryListSortEnum.DEFAULT.ordinal
        OnlinePlaylistsDescending = false
        LocalArtistsSort = LibraryListSortEnum.NAME.ordinal
        LocalArtistsDescending = false
        OnlineArtistsSort = LibraryListSortEnum.DEFAULT.ordinal
        OnlineArtistsDescending = false
        NowPlayingTranslation = true
        ShowLocalLibrary = true
        UseNewLibrary = true
        LibraryPinnedIds = ""
        RefreshEveryTime = false
        LyricFontWeight = "800"
        LyricLineBalance = false
        LyricDuetAlignment = true
        LyricBlurEffect = false
        LyricHdrEffect = false
        LyricHdrStrength = 0.6f
        NotificationLyricEnabled = true
        LyricFontScale = 1.0f
        LyricLandscapeFontScaleIndependent = false
        LyricLandscapeFontScale = 1.0f
        LyricCustomFontPath = ""
        LyricCustomFontName = ""
        SmartWordByWordLyric = false
        LyricTimingOffset = 0
        NowplayingBackgroundEffect = false
        NowplayingBackgroundMode = BACKGROUND_MODE_DRIFT
        NowplayingBackgroundFlowSpeed = 1
        NowplayingBackgroundPulse = true
        NowplayingBackgroundPulseStrength = 1
        NowplayingBackgroundBlurStrength = 1
        NowplayingBackgroundRenderQuality = 1
        NowplayingBackgroundPortraitPreset = 3
        NowplayingBackgroundLandscapePreset = 3
        NowplayingBackgroundRandomPreset = false
        GalleryFollowPlaybackBackground = true
        GalleryBackgroundMode = GALLERY_BG_DRIFT
        NowPlayingShowClimaxMark = true
        NowPlayingAutoHideStatusBar = false
        TrackBackdropGradient = false
        NowplayingStaticFullScreenAlbum = false
        NowPlayingDynamicCover = true
        NowPlayingDynamicCoverMobile = false
        BarBlurEffect = false
        GlassBlurScale = 1.0f
        FrostedBlurScale = 1.0f
        NotificationEnableIcon = true
        NotificationSmallerIcon = false
        ListenHistory = true
        UploadListeningDuration = true
        StatusBarLyricEnabled = false
        StatusBarLyricHooked = false
        AudioAttributes = true
        Codec = "Auto"
        HardwareAudioTrackPlayBackParams = false
        AudioFloatOutput = false
        VocalSeparationDefaultRatio = 0.5f
        OnlineQualityWifi = "high"
        OnlineQualityMobile = "128"
        ShowQualityDowngradeToast = true
        EnableExcludeSongsUnderOneMinute = true
        PlaybackSpeed = 1.0
        EnableFinishCurrentSongBeforePause = false
        CrossfadeEnabled = false
        CrossfadeDuration =
            yos.music.player.code.utils.player.CrossfadePolicy.DEFAULT_DURATION_SEC.toString()
        CrossfadeMode = CROSSFADE_MODE_SMOOTH
        SmartTransitionFxIntensity = 1
        DiagLogEnabled = true
        ColorOsLyricDiagEnabled = false
        AudioCacheCapMb = 1024
        ImageCacheCapMb = 512
    }

    const val BACKGROUND_MODE_DRIFT = 0
    const val BACKGROUND_MODE_MESH = 1

    const val GALLERY_BG_STATIC = 0
    const val GALLERY_BG_DRIFT = 1
    const val GALLERY_BG_MESH = 2

    const val CROSSFADE_MODE_SMOOTH = "Smooth"
    const val CROSSFADE_MODE_SMART = "Smart"
}
