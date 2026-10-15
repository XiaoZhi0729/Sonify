package yos.music.player.ui.navigation

/**
 * 路由 query 分量的 percent 编码（纯 Kotlin，不依赖 android.net.Uri）。
 *
 * 为什么不用 android.net.Uri.encode：它在纯 JVM 单测里是 framework stub
 * （unitTests.returnDefaultValues = true 下恒返回 null），使 toRoute() 的往返测试无法在
 * 本地跑；且更早的 URLEncoder 实现会把空格编成 "+"，而 NavController 不还原 "+"
 * → 带空格的歌单名变 "A+B"。
 *
 * 本实现按 RFC 3986：unreserved(A-Za-z0-9-._~) 原样，其余字节按 UTF-8 转 %XX（大写），
 * 空格因此编成 %20。转义集合比 Uri.encode 更严格（对 & = ? # + % 等 query 特义字符一律
 * 转义），对 query 分量只多转义、不少转义，与 NavController 的 query 解码配对安全；
 * 且与 [PlaylistSelection.fromRoute] 的 URLDecoder 往返一致（URLDecoder 无法区分 "+" 与
 * "%20"，故空格必须编成 %20）。
 */
internal fun routeEncode(value: String): String {
    val out = StringBuilder(value.length)
    for (b in value.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xFF
        val unreserved = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code ||
            c in '0'.code..'9'.code ||
            c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
        if (unreserved) out.append(c.toChar())
        else out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"
