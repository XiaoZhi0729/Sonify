package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/top/card/youth`（card_id=3006 VIP专属推荐 等卡片接口）条目解析单测。
 *
 * 走 [parseKmrSongFields]（Map 入参）：org.json 在 JVM 单测里是 android.jar 桩，
 * 不能直接构造 JSONObject——与 [KugouSpecialPlaylistParseTest] 同款约定。
 */
class KugouTopCardYouthParseTest {

    @Test
    fun parsesRealYouthCardPayloadAndPrefersAlbumCoverOverUgcPic() {
        val song = parseKmrSongFields(
            fields = mapOf(
                "hash" to "1e54c9c01c9dfc5c68f743368f353711",
                "author_name" to "王杰",
                "ori_audio_name" to "谁明浪子心",
                "suffix_audio_name" to "",
                "album_audio_id" to "32255261",
                "mixsongid" to 32255261L,
                "pic" to "https://youthimgbssdl.kugou.com/349ed0f9cfbc823143886db7b2b947d0.jpg",
                "cover_pic" to "https://youthimgbssdl.kugou.com/349ed0f9cfbc823143886db7b2b947d0.jpg",
                "album_info" to mapOf(
                    "album_name" to "谁明浪子心",
                    "album_id" to "983991",
                    "sizable_cover" to "http://imge.kugou.com/stdmusic/{size}/20250623/20250623075041557748.jpg"
                ),
                "audio_info" to mapOf(
                    "hash" to "1E54C9C01C9DFC5C68F743368F353711",
                    "timelength" to "301000",
                    "trans_param" to mapOf(
                        "union_cover" to "http://imge.kugou.com/stdmusic/{size}/20250623/20250623075041557748.jpg"
                    )
                )
            )
        )

        assertNotNull(song)
        song!!
        assertEquals("1e54c9c01c9dfc5c68f743368f353711", song.hash)
        assertEquals("谁明浪子心", song.name)
        assertEquals("王杰", song.author)
        assertEquals("谁明浪子心", song.albumName)
        assertEquals(301000L, song.durationMs)
        assertEquals(
            "https://imge.kugou.com/stdmusic/400/20250623/20250623075041557748.jpg",
            song.artworkUrl
        )
    }

    @Test
    fun fallsBackToAudioInfoUnionCoverAndAppendsSuffixAudioName() {
        val song = parseKmrSongFields(
            fields = mapOf(
                "hash" to "b78ba4062528e712b68c42dc6a4d9aae",
                "authors" to listOf(
                    mapOf("author_name" to "潘广益", "author_id" to "87404")
                ),
                "ori_audio_name" to "好想再爱你",
                "suffix_audio_name" to "(Live)",
                "pic" to "https://youthimgbssdl.kugou.com/ugc.png",
                "album_info" to mapOf(
                    "album_name" to "好想再爱你",
                    "sizable_cover" to ""
                ),
                "audio_info" to mapOf(
                    "timelength_128" to "270994",
                    "trans_param" to mapOf(
                        "union_cover" to "http://imge.kugou.com/stdmusic/{size}/20250207/20250207161344106383.jpg"
                    )
                )
            )
        )

        assertNotNull(song)
        song!!
        assertEquals("好想再爱你(Live)", song.name)
        assertEquals("潘广益", song.author)
        assertEquals(270994L, song.durationMs)
        assertEquals(
            "https://imge.kugou.com/stdmusic/400/20250207/20250207161344106383.jpg",
            song.artworkUrl
        )
    }

    @Test
    fun dropsItemWithoutHash() {
        assertNull(
            parseKmrSongFields(
                fields = mapOf("ori_audio_name" to "NoHash")
            )
        )
    }

    @Test
    fun parsesMusicStoryMetadataForNicheTreasureCard() {
        val song = parseKmrSongFields(
            fields = mapOf(
                "hash" to "23c4f1902e8cfeb816e4fd44e65007c9",
                "author_name" to "Jellycat、宋雨琦 (YUQI)",
                "ori_audio_name" to "Happy Space",
                "rec_info" to "  第一句\n  第二句  ",
                "nick_name" to "萌混过关",
                "user_pic" to "http://imge.kugou.com/kugouicon/165/20261118/20261118112328569102.jpg",
                "channel_name" to "「青春·解忧列车」",
                "pic" to "https://youthimgbssdl.kugou.com/a.jpg,https://youthimgbssdl.kugou.com/b.jpg",
                "album_info" to mapOf(
                    "album_name" to "Happy Space",
                    "sizable_cover" to "http://imge.kugou.com/stdmusic/{size}/20250514/20250514230412921917.jpg"
                ),
                "audio_info" to mapOf(
                    "timelength" to "198000"
                )
            )
        )

        assertNotNull(song)
        song!!
        assertEquals("第一句 第二句", song.recommendInfo)
        assertEquals("萌混过关", song.recommenderName)
        assertEquals(
            "https://imge.kugou.com/kugouicon/165/20261118/20261118112328569102.jpg",
            song.recommenderAvatarUrl
        )
        assertEquals("「青春·解忧列车」", song.channelName)
        assertEquals(
            "https://imge.kugou.com/stdmusic/400/20250514/20250514230412921917.jpg",
            song.artworkUrl
        )
    }
}
