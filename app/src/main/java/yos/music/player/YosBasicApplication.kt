package yos.music.player

import android.app.Application
import android.util.Log
import android.content.ComponentName
import android.net.Uri
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import com.funny.data_saver.core.DataSaverConverter.registerTypeConverters
import com.google.common.util.concurrent.MoreExecutors
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.code.MediaController.mediaControl
import yos.music.player.code.MediaController.playingMusicList
import yos.music.player.code.YosPlaybackService
import yos.music.player.data.libraries.Folder
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.PlayList
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.libraries.YosStringWrapper
import yos.music.player.data.objects.MediaViewModelObject
import kotlin.system.exitProcess

class YosBasicApplication : Application(), ImageLoaderFactory {

    /**
     * 全局 Coil ImageLoader：默认单例的内存缓存只占堆 25%，
     * 在资料库专辑网格 + 专辑详情 RAW 原图浏览后，主页封面会被逐出，
     * 回主页时触发批量磁盘解码 + crossfade 重放（表现为明显迟钝）。
     * 提升到 35% 增加 Home 封面在重度浏览后的存活率。
     *
     * respectCacheHeaders(false)：封面 URL 即内容标识（内容不变），而酷狗 CDN 返回的
     * 响应头（Date 远早于 max-age + Age 巨大）会把磁盘缓存判成永久过期，导致每次进
     * 歌单详情页都重新联网下载同一张封面（journal 实证每次 READ→DIRTY→CLEAN 51KB）。
     * 关闭后磁盘缓存永久有效，仅受 LRU 容量约束。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.35)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()

    override fun onCreate() {
        // 诊断通道最先起：下面任何一步（MMKV、native server、状态预热）崩了都要留下痕迹，
        // 而 release 下 println/Log 已被 R8 剥光，YosDiagnostics 是唯一会落盘的应用侧证据。
        yos.music.player.code.utils.others.YosDiagnostics.init(this)
        Log.d("POC_TEST", "YosBasicApplication.onCreate called")
        
        // Force load native library for POC verification
        try {
            Log.d("POC_TEST", "Accessing KugouNativeBridge to trigger .so loading...")
            yos.music.player.native.KugouNativeBridge
            Log.d("POC_TEST", "KugouNativeBridge object accessed successfully")
        } catch (e: Exception) {
            Log.e("POC_TEST", "Failed to access KugouNativeBridge: ${e.message}")
            e.printStackTrace()
        }

        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            e.printStackTrace()
            yos.music.player.code.utils.others.YosDiagnostics.crash(e.stackTraceToString())
            CrashActivity.startActivity(this, e.stackTraceToString())
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(1)
        }

        // 初始化 MMKV
        MMKV.initialize(this)
        // MMKV 就绪后才能安全读设置（YosDataSaver 构造即取 MMKV 实例），把诊断开关取进内存
        yos.music.player.code.utils.others.YosDiagnostics.syncEnabledFromSettings()

        // 冷启动登录态恢复：紧跟 MMKV 初始化，只读 kugou_auth，
        // 与 Rust Server 启动是两件独立的事——server 仍由 KugouApiService.ensureServer()
        // 在真正调用酷狗 API 时按需启动。此处保证任何页面组合前 isLoggedIn() 即可用。
        yos.music.player.native.KugouApiService.ensureAuthRestored()

        // 预热全局 Compose 状态 object：object 内的 mutableStateOf 若首次初始化发生在
        // 某次重组（如开屏 AnimatedContent 过渡）内部，该重组被放弃时 snapshot 未 apply，
        // 后续重组读取会抛 "Reading a state that was created after the snapshot was
        // taken"。在此（主线程、任何组合之前）触碰一次，强制初始化落在全局快照上下文。
        runCatching {
            yos.music.player.data.objects.KugouAccountState.isLoggedIn
            yos.music.player.data.repositories.KugouRepository.favoriteHashes.value
            yos.music.player.data.objects.SearchObject.status.value
            yos.music.player.data.objects.DiscoveryObject.status.value
            yos.music.player.data.objects.KugouVipState.isSignedToday()
            yos.music.player.data.repositories.KugouVipRepository.AutoReceiveVip
            // 音质链路的全局状态与网络监听同样在主线程、任何组合之前预热：
            // 徽标与菜单要在首帧就能读到真实网络形态，而不是等第一次解析才知道
            yos.music.player.data.repositories.PerSongQualityIntent.version.intValue
            yos.music.player.data.repositories.NetworkObserver.version.intValue
            yos.music.player.data.repositories.KugouRepository.actualQualityVersion.intValue
            // 本曲覆盖表现在跨重启保留了：在主线程把它读进内存，避免首帧组合时才去碰磁盘
            yos.music.player.data.repositories.PerSongQualityIntent.preload()
            yos.music.player.data.objects.ArtistPresentationCache.preload()
        }

        // 音质切换要读偏好与网络形态：主进程侧提前注入应用级 Context（服务侧也会注入一次）
        yos.music.player.code.MediaController.appContext = applicationContext
        yos.music.player.data.repositories.NetworkObserver.init(this)
        yos.music.player.data.repositories.QualityTrace.strict =
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

        // 酷狗 VIP 自动签到（对齐 md3Music autoReceiveVipIfNeeded）：
        // 登录态 + 开关开启 + 本地今天未签才会发请求（本地已签则完全不拉起 Rust server）。
        // 延迟 5s 错开冷启动渲染高峰；请求体在 KugouVipRepository，20028 不做二次验证（与参考实现一致）。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { delay(5000) }
            runCatching { yos.music.player.data.repositories.KugouVipRepository.autoSignInIfNeeded() }
        }

        val gson =
            GsonBuilder()
            //.registerTypeAdapter(Uri::class.java, UriSerializer())
            //registerTypeAdapter(Uri::class.java, UriDeserializer())
            .registerTypeAdapter(Uri::class.java, UriTypeAdapter())
            .create()

        registerTypeConverters(
            save = { bean -> gson.toJson(bean) },
            restore = { str -> gson.fromJson(str, Folder::class.java) }
        )

        /*registerTypeConverters(
            save = { bean -> gson.toJson(bean) },
            restore = { str -> gson.fromJson(str, ImmutableList::class.java) }
        )

        registerTypeConverters(
            save = { bean -> gson.toJson(bean) },
            restore = { str -> gson.fromJson(str, ArrayList::class.java) }
        )*/

        registerTypeConverters(
            save = { bean -> gson.toJson(bean) },
            restore = { str -> gson.fromJson(str, PlayList::class.java) }
        )

        registerTypeConverters(
            save = { bean -> gson.toJson(bean) },
            restore = { str -> gson.fromJson(str, IntArray::class.java) }
        )

        registerTypeConverters(
            save = { bean -> gson.toJson(bean) },
            restore = { str -> gson.fromJson(str, YosMediaItem::class.java) }
        )

        registerTypeConverters(
            save = { bean -> gson.toJson(bean) },
            restore = { str -> gson.fromJson(str, YosStringWrapper::class.java) }
        )

        // 初始化媒体控制器
        val sessionToken = SessionToken(this, ComponentName(this, YosPlaybackService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                mediaControl = controllerFuture.get()
                mediaControl?.let { controller ->
                    MediaViewModelObject.shuffleModeEnabled.value = controller.shuffleModeEnabled
                    MediaViewModelObject.repeatMode.intValue = controller.repeatMode
                }

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val playListData = MusicLibrary.loadPlayList()
                        val playStatusData = MusicLibrary.loadPlayStatus()

                        println("prepare 读取历史")
                        if (playListData.mainMusicList != null) {
                            println("prepare 准备调用")
                            /*launch {
                                runCatching {
                                    // 无论设置与否，先还原
                                    val thisMainMusicList = playListData.mainMusicList
                                    mainMusicList.value = thisMainMusicList
                                    println("prepare 恢复主歌单")
                                }
                            }*/

                            if (playStatusData.music != null) {
                                yos.music.player.code.MediaController.prepare(
                                    playStatusData.music,
                                    playListData.playingMusicList!!,
                                    playStatusData.position,
                                    playStatusData.shuffleModeEnabled,
                                    playStatusData.repeatMode,
                                    false
                                )
                            }

                            if (playListData.playingMusicList != null) {
                                // 该协程在 IO 线程;state 写入须回到 Main,避免主线程
                                // 组合/measure 中途读到换帧数据(见 PlayingList 越界崩溃)
                                withContext(Dispatchers.Main) {
                                    playingMusicList.value = playListData.playingMusicList
                                }
                            }
                        }

                        // 云端收藏同步：登录态已在 ensureAuthRestored() 恢复，此处同步一次酷狗
                        // 「我喜欢」（is_def=2）→ 内存 hash 集合，供心形按钮读取；
                        // 未登录时快速返回不启 server；失败仅影响收藏状态显示，不影响播放。
                        runCatching {
                            yos.music.player.data.repositories.KugouRepository.syncKugouFavorites()
                        }
                    } catch (e:Exception) {
                        e.printStackTrace()
                    }
                }
            },
            MoreExecutors.directExecutor()
        )

        super.onCreate()
    }
}


/*
class ImmutableListTypeAdapter<T> : JsonSerializer<ImmutableList<T>>,
    JsonDeserializer<ImmutableList<T>> {
    override fun serialize(src: ImmutableList<T>?, typeOfSrc: Type?, context: JsonSerializationContext?): JsonElement {
        return context?.serialize(src?.toList()) ?: JsonNull.INSTANCE
    }

    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: JsonDeserializationContext?): ImmutableList<T> {
        val listType = object : TypeToken<List<T>>() {}.type
        val list = context?.deserialize<List<T>>(json, listType)
        return ImmutableList.copyOf(list)
    }
}*/

class UriTypeAdapter : TypeAdapter<Uri>() {
    override fun write(out: JsonWriter, value: Uri?) {
        out.value(value.toString())
    }

    override fun read(`in`: JsonReader): Uri {
        return Uri.parse(`in`.nextString())
    }
}

/*
class UriSerializer : JsonSerializer<Uri> {
    override fun serialize(src: Uri?, typeOfSrc: Type?, context: com.google.gson.JsonSerializationContext?): JsonElement {
        return JsonPrimitive(src.toString())
    }
}

class UriDeserializer : JsonDeserializer<Uri> {
    override fun deserialize(json: JsonElement?, typeOfT: Type?, context: com.google.gson.JsonDeserializationContext?): Uri {
        return Uri.parse(json?.asString)
    }
}*/
