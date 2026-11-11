package yos.music.player.ui.widgets.basic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 播放页高清封面 URL 改写：数据层把 `{size}` 统一解析成 400（列表缩略图够用且省流量），
 * 播放页大封面要 ≈1137 物理像素，这里只改播放页消费方的那几处 URL。
 *
 * 改写必须**保守**：认不出是酷狗图床、或路径里没有尺寸档位段，就原样返回。
 * 改错一处 = 封面 404 变占位图，比"不够清晰"严重得多。
 */
class HiresCoverUrlTest {

    @Test
    fun singleDirectoryHostGetsUpgraded() {
        assertEquals(
            "https://c1.kgimg.com/stdmusic/1080/20210120/20210120122701995697.jpg",
            hiresCoverUrl("https://c1.kgimg.com/stdmusic/400/20210120/20210120122701995697.jpg")
        )
        assertEquals(
            "https://imge.kugou.com/stdmusic/1080/20241227/20241227183406542733.jpg",
            hiresCoverUrl("https://imge.kugou.com/stdmusic/400/20241227/20241227183406542733.jpg")
        )
    }

    @Test
    fun nestedDirectoryHostGetsUpgraded() {
        // 歌手头像走 uploadpic/softhead/<size>/，主机的两个目录段都保留
        assertEquals(
            "https://singerimg.kugou.com/uploadpic/softhead/1080/20200918/20200918213144462773.jpg",
            hiresCoverUrl("https://singerimg.kugou.com/uploadpic/softhead/400/20200918/20200918213144462773.jpg")
        )
    }

    @Test
    fun httpIsUpgradedWithoutChangingScheme() {
        assertEquals(
            "http://c1.kgimg.com/custom/1080/20240417/20240417003011305060.jpg",
            hiresCoverUrl("http://c1.kgimg.com/custom/400/20240417/20240417003011305060.jpg")
        )
    }

    @Test
    fun yearDirectoryAfterNamedDirectoryIsNotMistakenForSize() {
        // `custom/<size>/<date>/` 是对的，但 `/2024/0417/` 这种年份+月日形状里没有尺寸段：
        // 档位段的判定要求后一段是 8 位日期，`0417` 只有 4 位，所以整条 URL 原样返回。
        val url = "https://c1.kgimg.com/stdmusic/2024/0417/cover.jpg"
        assertEquals(url, hiresCoverUrl(url))
    }

    @Test
    fun alreadyTargetSizeReturnsSameInstance() {
        val url = "https://c1.kgimg.com/stdmusic/1080/20210120/20210120122701995697.jpg"
        // 同一个实例：调用方把返回值当 remember 的键，不能每帧造新串
        assertSame(url, hiresCoverUrl(url))
    }

    @Test
    fun localFileAndContentUrisAreUntouched() {
        listOf(
            "/storage/emulated/0/Music/cover.jpg",
            "file:///storage/emulated/0/Music/cover.jpg",
            "content://media/external/audio/albumart/42"
        ).forEach { assertEquals(it, hiresCoverUrl(it)) }
    }

    @Test
    fun nonKugouHostIsUntouched() {
        assertEquals(
            "https://example.com/stdmusic/400/20210120/x.jpg",
            hiresCoverUrl("https://example.com/stdmusic/400/20210120/x.jpg")
        )
        // 后缀相同但其实是别的域名，不能误判
        assertEquals(
            "https://notkgimg.com/stdmusic/400/20210120/x.jpg",
            hiresCoverUrl("https://notkgimg.com/stdmusic/400/20210120/x.jpg")
        )
    }

    @Test
    fun urlWithoutSizeSegmentIsUntouched() {
        assertEquals(
            "https://c1.kgimg.com/stdmusic/20210120122701995697.jpg",
            hiresCoverUrl("https://c1.kgimg.com/stdmusic/20210120122701995697.jpg")
        )
    }

    @Test
    fun nullStaysNull() {
        assertEquals(null, hiresCoverUrl(null))
    }

    @Test
    fun targetSizeIsTheLastPreGeneratedTier() {
        // 1080 是酷狗预生成档位里最大的一档（240/400/720/1080），超过它都是服务端放大：
        // 实测 1500 档与"1080 上采样"相差不到 1.5dB 高频能量，没有新增细节。
        assertEquals(1080, PlaybackCoverSize)
    }

    @Test
    fun artistHeroPathIsUpgradedLikeSongCover() {
        // 歌手页 hero 是满屏宽（1280 物理像素），400 源要放大 3.2 倍；
        // 歌手图与歌曲封面走同一条 resolveArtworkUrl，形状也是 `<命名目录>/<档位>/<8位日期>/`。
        assertEquals(
            "https://singerimg.kugou.com/uploadpic/softhead/1080/20251030/20251030153830755874.jpg",
            hiresCoverUrl("https://singerimg.kugou.com/uploadpic/softhead/400/20251030/20251030153830755874.jpg")
        )
    }

    @Test
    fun rewritingIsIdempotent() {
        // 歌手页把改写提到源头后，paletteKey / bakeCacheKey / memoryCacheKey 都由改写结果派生。
        // 万一中间又过一遍改写，结果必须不变（否则缓存键会漂移，回访永远 miss）。
        val once = hiresCoverUrl("https://singerimg.kugou.com/uploadpic/softhead/400/20200918/x.jpg")
        assertEquals(once, hiresCoverUrl(once))
    }
}
