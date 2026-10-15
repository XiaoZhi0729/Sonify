package yos.music.player.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLDecoder

/**
 * [routeEncode] 的 percent 编码契约。它是路由 query 分量的编码端，必须与
 * [PlaylistSelection.fromRoute] 的 [URLDecoder] 解码端严格往返一致。
 *
 * 本类同时是「为什么不用 android.net.Uri.encode」的回归闸：该 API 在纯 JVM 单测里是
 * framework stub（returnDefaultValues 下返回 null），此前正是它让 toRoute() 往返测试
 * 在本地永远红。
 */
class RouteEncodingTest {

    @Test
    fun spaceEncodesToPercent20() {
        // 回归 "A+B" 老 bug：URLEncoder 会把空格编成 "+"，而 NavController / URLDecoder
        // 语义下 "+" 与 "%20" 在 query 里含义不同，必须用 %20。
        assertEquals("A%20B", routeEncode("A B"))
    }

    @Test
    fun keepsUnreservedUntouched() {
        // RFC 3986 unreserved：字母数字与 - . _ ~ 原样通过
        assertEquals("aZ09-._~", routeEncode("aZ09-._~"))
    }

    @Test
    fun escapesQueryDelimiters() {
        // query 分隔/特义字符必须转义，否则参数会串位（name=A&B 会被拆成两个参数）
        assertEquals("A%26B", routeEncode("A&B"))
        assertEquals("a%3Db", routeEncode("a=b"))
        assertEquals("a%3Fb", routeEncode("a?b"))
        assertEquals("a%23b", routeEncode("a#b"))
        assertEquals("a%2Bb", routeEncode("a+b"))
        assertEquals("a%25b", routeEncode("a%b"))
    }

    @Test
    fun nonAsciiUsesUtf8PercentBytes() {
        // 多字节字符按 UTF-8 逐字节转义（「夜」= E5 A4 9C）
        assertEquals("%E5%A4%9C", routeEncode("夜"))
    }

    @Test
    fun roundTripsThroughUrlDecoder() {
        // 与解码端（PlaylistSelection.fromRoute 用 URLDecoder）必须逐字符往返。
        // 覆盖歌单路由真实会带的字符：空格、& / ? # 等特义、中文、URL。
        val samples = listOf(
            "",
            "gc/42?x=1",
            "A&B / 夜歌",
            "https://img.example/a?size=400&x=1",
            "aZ09-._~ !'()*",
            "夜歌 100%"
        )
        for (s in samples) {
            assertEquals(s, URLDecoder.decode(routeEncode(s), "UTF-8"))
        }
    }
}
