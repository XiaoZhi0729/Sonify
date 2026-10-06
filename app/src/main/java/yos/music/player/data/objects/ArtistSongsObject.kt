package yos.music.player.data.objects

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import yos.music.player.data.repositories.ArtistAlbumPage
import yos.music.player.data.repositories.ArtistAudioPage
import yos.music.player.data.repositories.KugouNewAlbum
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRepository
import kotlin.coroutines.cancellation.CancellationException

/** 详情与全部歌曲共享同一艺人的数据；不同导航栈的艺人彼此隔离。 */
object ArtistSongsObject {
    private val states = mutableMapOf<String, ArtistSongsState>()

    fun forArtist(id: String): ArtistSongsState = synchronized(states) {
        states.getOrPut(id) { ArtistSongsState(id) }
    }
}

/** 热门歌曲（sort=hot）分页数据：与详情页预览、热门歌曲大全页共享同一实例。 */
object ArtistHotSongsObject {
    private val states = mutableMapOf<String, ArtistSongsState>()

    fun forArtist(id: String): ArtistSongsState = synchronized(states) {
        states.getOrPut(id) { ArtistSongsState(id, sort = "hot") }
    }
}

/** 艺人专辑分页数据：详情页首屏与专辑大全页共享同一实例。 */
object ArtistAlbumsObject {
    private val states = mutableMapOf<String, ArtistAlbumsState>()

    fun forArtist(id: String, name: String): ArtistAlbumsState = synchronized(states) {
        states.getOrPut(id) { ArtistAlbumsState(id, name) }
    }
}

@Stable
class ArtistSongsState(
    val artistId: String,
    private val sort: String = "",
    private val fetchPage: suspend (String, Int) -> Result<ArtistAudioPage> = { id, page ->
        KugouRepository.getArtistAudioPage(id, page, sort = sort)
    },
    private val requestScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    val songs = mutableStateOf<List<KugouNewSong>>(emptyList())
    val page = mutableStateOf(1)
    val loading = mutableStateOf(false)
    val endReached = mutableStateOf(false)
    val loadError = mutableStateOf<String?>(null)
    val paletteColor = mutableStateOf<Int?>(null)
    var paletteUrl: String? = null
    private val mutex = Mutex()

    fun ensureLoaded() {
        if (songs.value.isEmpty() && !endReached.value && loadError.value == null) requestNextPage()
    }

    // 首拉跨页面接续，不能随详情页离开组合而取消。
    fun requestNextPage() {
        requestScope.launch { loadNextPage() }
    }

    suspend fun loadNextPage(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (artistId.isEmpty() || endReached.value || !mutex.tryLock()) return@withContext false
        loading.value = true
        loadError.value = null
        try {
            val response = fetchPage(artistId, page.value).getOrThrow()
            val known = songs.value.mapTo(mutableSetOf()) { it.hash }
            val additions = response.songs.filter { it.hash.isNotEmpty() && known.add(it.hash) }
            songs.value = songs.value + additions
            endReached.value = response.rawCount < 30
            page.value += 1
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError.value = e.message ?: e.javaClass.simpleName
            false
        } finally {
            loading.value = false
            mutex.unlock()
        }
    }
}

@Stable
class ArtistAlbumsState(
    val artistId: String,
    private val artistName: String,
    private val fetchPage: suspend (String, String, Int) -> Result<ArtistAlbumPage> = { id, name, page ->
        KugouRepository.getArtistAlbumPage(id, name, page)
    },
    private val requestScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    val albums = mutableStateOf<List<KugouNewAlbum>>(emptyList())
    val page = mutableStateOf(1)
    val loading = mutableStateOf(false)
    val endReached = mutableStateOf(false)
    val loadError = mutableStateOf<String?>(null)
    private val mutex = Mutex()

    fun ensureLoaded() {
        if (albums.value.isEmpty() && !endReached.value && loadError.value == null) requestNextPage()
    }

    fun requestNextPage() {
        requestScope.launch { loadNextPage() }
    }

    suspend fun loadNextPage(): Boolean = withContext(Dispatchers.Main.immediate) {
        if (artistId.isEmpty() || endReached.value || !mutex.tryLock()) return@withContext false
        loading.value = true
        loadError.value = null
        try {
            val response = fetchPage(artistId, artistName, page.value).getOrThrow()
            val known = albums.value.mapTo(mutableSetOf()) { it.albumId }
            val additions = response.albums.filter { it.albumId.isNotEmpty() && known.add(it.albumId) }
            albums.value = albums.value + additions
            endReached.value = response.rawCount < 30
            page.value += 1
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError.value = e.message ?: e.javaClass.simpleName
            false
        } finally {
            loading.value = false
            mutex.unlock()
        }
    }
}
