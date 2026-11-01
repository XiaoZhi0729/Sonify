@file:Suppress("SameParameterValue")

package yos.music.player.data.libraries

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.util.fastMap
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.funny.data_saver.core.mutableDataSaverListStateOf
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import uk.akane.libphonograph.constructor.ItemConstructor
import uk.akane.libphonograph.reader.Reader
import uk.akane.libphonograph.reader.ReaderConfiguration
import uk.akane.libphonograph.reader.ReaderResult
import yos.music.player.UriTypeAdapter
import yos.music.player.code.MediaController
import yos.music.player.data.NormalSaver
import yos.music.player.data.SongListSaver
import yos.music.player.data.repositories.KugouRepository.withPlayableUri

/*@Parcelize
@Stable
data class Music(
    val title: String,
    val artist: List<String>,
    val album: String,
    val path: String,
    val date: Long,
    val id: Long,
    var thumb: String?,
    val duration: Long = 0,
    val bitrate: Int,
    val samplingRate: Int
) : Parcelable {
    fun artistsToString(): String {
        return artist.joinToString("、")
    }
}*/

@Stable
data class Time(
    val min: String,
    val sec: String
)
@Parcelize
@Stable
data class PlayListV1(
    val mainMusicList: List<YosMediaItem>?,
    val playingMusicList: List<YosMediaItem>?
) : Parcelable

@Parcelize
@Stable
data class PlayStatus(
    val music: YosMediaItem?,
    val position: Long,
    val shuffleModeEnabled: Boolean,
    val repeatMode: Int
) : Parcelable

@Parcelize
@Stable
data class Folder(val name: String, val path: String, val songs: List<YosMediaItem>) : Parcelable

@Parcelize
@Stable
data class YosMediaItem(
    val uri: Uri?,
    val mediaId: String?,
    val mimeType: String?,
    val title: String?,
    val writer: String?,
    val compilation: String?,
    val composer: String?,
    val artists: String?,
    val album: String?,
    val albumArtists: String?,
    val thumb: Uri?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val genre: String?,
    val recordingDay: Int?,
    val recordingMonth: Int?,
    val recordingYear: Int?,
    val releaseYear: Int?,
    val artistId: Long?,
    val albumId: Long?,
    val genreId: Long?,
    val author: String?,
    val addDate: Long?,
    val duration: Long,
    val modifiedDate: Long?,
    val cdTrackNumber: Int?
    //val samplingRate: Int,
    //val bitrate: Int
) : Parcelable

@Stable
@Parcelize
data class YosStringWrapper(val value: String) : Parcelable

@Stable
object MusicLibrary {
    // yos_player_core 负责歌曲列表 V1、播放状态记录

    private const val mmkvID = "yos_player_core"
    private const val playListKey = "yos_play_list_v1"
    private const val playStatusKey = "yos_player_play_status"
    private const val RECENTLY_PLAYED_LIMIT = 100

    var hideSongs by mutableDataSaverListStateOf(
        dataSaverInterface = SongListSaver,
        key = "hide_songs",
        initialValue = listOf<YosMediaItem>()
    )
        private set

    var folders by mutableDataSaverListStateOf(
        dataSaverInterface = SongListSaver,
        key = "folders",
        initialValue = listOf<Folder>()
    )
        private set

    // 最近播放：按收听时间倒序累积的历史（同曲去重、上限 100 条），
    // YosPlaybackService 切歌时调用 recordRecentlyPlayed 写入；设置「播放历史」关闭时停止记录
    var recentlyPlayed by mutableDataSaverListStateOf(
        dataSaverInterface = SongListSaver,
        key = "recently_played_v1",
        initialValue = listOf<YosMediaItem>()
    )
        private set

    private var hideFoldersSaver by mutableDataSaverListStateOf(
        dataSaverInterface = NormalSaver, key = "hide_folders", initialValue = listOf<YosStringWrapper>()
    )

    val hideFolders: List<String>
        get() = hideFoldersSaver.map { it.value }

    private var songSaver by mutableDataSaverListStateOf(
        dataSaverInterface = SongListSaver, key = "songs", initialValue = listOf<YosMediaItem>()
    )

    // 缓存全量可见歌曲：依赖 songSaver/hideSongs/folders/hideFolders 变化才重算。
    // 原 getter 在 filter 内嵌套 folders flatMap，每次访问 O(N×H) 主线程重算，
    // 滑动列表逐行触发导致严重卡顿
    private val songsCache = derivedStateOf {
        val hiddenUris = folders
            .filter { it.path in hideFolders }
            .flatMap { it.songs }
            .map { it.uri }
            .toHashSet()
        val hiddenSongs = hideSongs.toHashSet()
        songSaver.filter { it !in hiddenSongs && it.uri !in hiddenUris }
    }

    val songs: List<YosMediaItem>
        get() = songsCache.value

    // 艺人→歌曲 / 专辑→歌曲 索引：一次构建，按名查询 O(1)，
    // 避免每次 Artist[name]/Album[name] 全库 filter
    private val artistIndexCache = derivedStateOf {
        val index = HashMap<String, MutableList<YosMediaItem>>()
        for (song in songsCache.value) {
            for (artist in song.artistsList ?: defaultArtists) {
                index.getOrPut(artist) { mutableListOf() }.add(song)
            }
        }
        index
    }

    private val albumIndexCache = derivedStateOf {
        val index = HashMap<String, MutableList<YosMediaItem>>()
        for (song in songsCache.value) {
            index.getOrPut(song.album ?: defaultAlbum) { mutableListOf() }.add(song)
        }
        index
    }

    val artists
        get() = songs.flatMap {
            it.artistsList ?: defaultArtists
        }
            .distinct()

    val albums
        get() = songs.distinctBy { it.album ?: defaultAlbum }.map { it.album ?: defaultAlbum }

    @Stable
    object Album {
        operator fun get(albumName: String) =
            albumIndexCache.value[albumName] ?: emptyList()
    }

    @Stable
    object Artist {
        operator fun get(artistName: String) =
            artistIndexCache.value[artistName] ?: emptyList()
    }

    fun updatePlayList(playListV1: PlayListV1) {
        updateData(playListKey, playListV1)
        println("updatePlayList $playListV1")
    }

    fun loadPlayList(): PlayListV1 {
        val loadedData = loadData(playListKey) ?: PlayListV1(null, null)
        println("loadPlayList $loadedData")
        return loadedData
    }

    fun recordRecentlyPlayed(item: YosMediaItem) {
        if (!SettingsLibrary.ListenHistory) return
        // mediaId 是本地/在线歌曲的稳定唯一键，uri 兜底；两者皆空无法去重，跳过
        val recordKey = item.mediaId ?: item.uri?.toString() ?: return
        recentlyPlayed = buildList {
            add(item)
            addAll(recentlyPlayed.filter { (it.mediaId ?: it.uri?.toString()) != recordKey })
        }.take(RECENTLY_PLAYED_LIMIT)
        println("recordRecentlyPlayed ${item.title}，历史 ${recentlyPlayed.size} 条")
    }

    fun MediaItem.toYosMediaItem(): YosMediaItem {
        return YosMediaItem(
            uri = this.localConfiguration?.uri,
            mediaId = this.mediaId,
            mimeType = this.localConfiguration?.mimeType,
            title = this.title,
            writer = this.writer,
            compilation = this.compilation,
            composer = this.composer,
            artists = this.artistsName,
            album = this.album,
            albumArtists = this.albumArtists,
            thumb = this.thumb,
            trackNumber = this.trackNumber,
            discNumber = this.discNumber,
            genre = this.genre,
            recordingDay = this.recordingDay,
            recordingMonth = this.recordingMonth,
            recordingYear = this.recordingYear,
            releaseYear = this.releaseYear,
            artistId = this.artistId,
            albumId = this.albumId,
            genreId = this.genreId,
            author = this.author,
            addDate = this.addDate,
            duration = this.duration,
            modifiedDate = this.modifiedDate,
            cdTrackNumber = this.cdTrackNumber
            //samplingRate = this.samplingRate,
            //bitrate = this.bitrate
        )
    }

    fun YosMediaItem.toMediaItem(): MediaItem {
        // 纯展示条目（uri=null）也要能进队列：在线歌按 mediaId 补回 kugou:// 占位符，
        // 否则 MediaItem 没有 localConfiguration，播到它必报 FileDataSource ENOENT。
        val source = withPlayableUri()
        return MediaItem.Builder()
            .setUri(source.uri)
            .setMediaId(source.mediaId!!)
            .setMimeType(source.mimeType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(this.title)
                    .setWriter(this.writer)
                    .setCompilation(this.compilation)
                    .setComposer(this.composer)
                    .setArtist(this.artists)
                    .setAlbumTitle(this.album)
                    .setAlbumArtist(this.albumArtists)
                    .setArtworkUri(this.thumb)
                    .setTrackNumber(this.trackNumber)
                    .setDiscNumber(this.discNumber)
                    .setGenre(this.genre)
                    .setRecordingDay(this.recordingDay)
                    .setRecordingMonth(this.recordingMonth)
                    .setRecordingYear(this.recordingYear)
                    .setReleaseYear(this.releaseYear)
                    // 自有字段（ArtistId/AlbumId/Duration 等）不得写入 extras：发布给
                    // session 的条目 extras 必须保持 null。media3 向 framework 发布元数据
                    // 前用 MediaMetadata.equals 去重，而它对 extras 只比 null 与非 null
                    // 不比内容——条目自带非空 extras 时，后续 lyricInfo 写入无法触发
                    // 重发布（ColorOS 锁屏歌词收不到歌词的根因）。这些字段经
                    // playingMusicList 进程内传递，session 往返读回处按 mediaId 合并
                    // （syncQueueSnapshot / handleTrackSwitched）。扫描路径的
                    // readerConfiguration extras 是进程内 Reader→YosMediaItem 中转，不受影响。
                    .build()
            )
            .build()
    }

    fun updatePlayStatus(playStatus: PlayStatus) {
        updateData(playStatusKey, playStatus)
    }

    fun loadPlayStatus(): PlayStatus {
        return loadData(playStatusKey) ?: PlayStatus(null, 0L, false, 0)
    }

    // 播放状态/队列的读写落在播放、切歌的热路径上，且每次冷启动都要在主线程反序列化
    // 一次播放状态。GsonBuilder.create() 要遍历注册全部 TypeAdapterFactory，不是零成本，
    // 而 Gson 实例本身不可变、线程安全 —— 建一次复用即可（UriTypeAdapter 无状态）。
    private val gson: com.google.gson.Gson by lazy {
        GsonBuilder().registerTypeAdapter(Uri::class.java, UriTypeAdapter()).create()
    }

    private inline fun <reified T> updateData(key: String, value: T) {
        val json = gson.toJson(value)
        MMKV.mmkvWithID(mmkvID).encode(key, json)
    }

    private inline fun <reified T> loadData(key: String): T? {
        val json = MMKV.mmkvWithID(mmkvID).decodeString(key) ?: return null
        val type = object : TypeToken<T>() {}.type
        return gson.fromJson(json, type)
    }

    fun hideFolder(folder: Folder) {
        updateFolderVisibility(folder, hide = true)
    }

    fun unHideFolder(folder: Folder) {
        updateFolderVisibility(folder, hide = false)
    }

    fun hideSong(song: YosMediaItem) {
        hideSongs = hideSongs + song
    }

    fun unHideSong(song: YosMediaItem) {
        hideSongs = hideSongs - song
    }

    /*fun removeSong(song: YosMediaItem) {
        folders = folders.map {
            if (it.songs.contains(song)) {
                return@map it.copy(
                    name = it.name,
                    songs = it.songs.toMutableList().apply { remove(song) })
            }
            it
        }
        hideSongs = hideSongs - song
    }*/

    private fun updateFolderVisibility(folder: Folder, hide: Boolean) {
        println("文件夹 显示状态更改")
        if (hide) {
            println("文件夹 将隐藏 $folder")
            if (folders.any { it.path == folder.path }) {
                // folders = folders - folder
                println("文件夹 匹配成功")
                println("文件夹 已将 ${folder.path} 隐藏")
                hideFoldersSaver = hideFoldersSaver.plus(YosStringWrapper(folder.path))
            }
        } else {
            println("文件夹 将显示 $folder")
            if (hideFolders.any { it == folder.path }) {
                // folders = folders + folder
                println("文件夹 匹配成功")
                println("文件夹 已将 ${folder.path} 显示")
                hideFoldersSaver = hideFoldersSaver.minus(YosStringWrapper(folder.path))
            }
        }
    }

    private val readerConfiguration = ReaderConfiguration(
        ItemConstructor { uri, mediaId, mimeType, title, writer, compilation,
                          composer, artist, albumTitle, albumArtist, artworkUri,
                          cdTrackNumber, trackNumber, discNumber, genre,
                          recordingDay, recordingMonth, recordingYear, releaseYear,
                          artistId, albumId, genreId, author, addDate,
                          duration, modifiedDate ->
            //val audioProperties = getAudioProperties(uri.path!!)
            return@ItemConstructor MediaItem
                .Builder()
                .setUri(uri)
                .setMediaId(mediaId.toString())
                .setMimeType(mimeType)
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .setTitle(title)
                        .setWriter(writer)
                        .setCompilation(compilation)
                        .setComposer(composer)
                        .setArtist(artist)
                        .setAlbumTitle(albumTitle)
                        .setAlbumArtist(albumArtist)
                        .setArtworkUri(artworkUri)
                        .setTrackNumber(trackNumber)
                        .setDiscNumber(discNumber)
                        .setGenre(genre)
                        .setRecordingDay(recordingDay)
                        .setRecordingMonth(recordingMonth)
                        .setRecordingYear(recordingYear)
                        .setReleaseYear(releaseYear)
                        .setExtras(Bundle().apply {
                            if (artistId != null) {
                                putLong("ArtistId", artistId)
                            }
                            if (albumId != null) {
                                putLong("AlbumId", albumId)
                            }
                            if (genreId != null) {
                                putLong("GenreId", genreId)
                            }
                            putString("Author", author)
                            if (addDate != null) {
                                putLong("AddDate", addDate)
                            }
                            if (duration != null) {
                                putLong("Duration", duration)
                            }
                            if (modifiedDate != null) {
                                putLong("ModifiedDate", modifiedDate)
                            }
                            cdTrackNumber?.toIntOrNull()
                                ?.let { it1 -> putInt("CdTrackNumber", it1) }
                            /*audioProperties?.let {
                                putInt("SamplingRate", it.first)
                                putInt("Bitrate", it.second)
                            }*/
                        })
                        .build(),
                ).build()
        },
        shouldFetchPlaylist = true,
        shouldIncludeExtraFormat = true
    )

    suspend fun scanMedia(context: Context): ReaderResult<MediaItem> {
        return withContext(Dispatchers.IO) {
            val result = Reader.readFromMediaStore(
                context,
                readerConfiguration
            )

            // 有层级结构的result.folderStructure.folderList[""].folderList
            // val folderList = result.folderStructure.folderList

            songSaver = result.songList.fastMap {
                it.toYosMediaItem()
            }

            result.shallowFolder.folderList.map {
                val name = it.key
                val path = it.value.songList.first().uri?.path?.substringBeforeLast("/")?:""
                val songs = it.value.songList.fastMap { thisSong ->
                    thisSong.toYosMediaItem()
                }
                Folder(name, path, songs)
            }.let {
                folders = it
            }

            /*folders = folderList.map { (path, fileNode) ->
                Folder(
                    path,
                    fileNode.songList.toList()
                )
            }.filter { folder ->
                folder !in hideFolders
            }*/

            println("基本扫描: ${result.songList}")
            println("文件夹: $folders")
            println("SongSaver: $songSaver")
            println("Songs: $songs")

            println("prepare 媒体库扫描完毕，尝试保存播放列表")
            println("prepare 媒体库扫描完毕，保存播放列表")

            // 播放队列快照尚未由恢复链填充时不能落盘空队列：无本地音乐的设备上扫描
            // 瞬间完成，会赶在 loadPlayList 之前用空队列覆盖持久化，之后恢复读到空队列
            // 再原样写回——每次重启都丢队列。恢复链（prepare）稍后会用真实数据落盘。
            MediaController.playingMusicList.value?.let {
                updatePlayList(
                    PlayListV1(
                        MediaController.mainMusicList,
                        it
                    )
                )
            }

            result
        }
    }
}
