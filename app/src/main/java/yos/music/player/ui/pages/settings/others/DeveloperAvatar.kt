package yos.music.player.ui.pages.settings.others

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import yos.music.player.R
import kotlin.random.Random

private val AvatarSize = 42.dp

// 贡献者头像轮播：每 0.1s 直接换一张（无淡化、无描边闪动），随机且不连续重复。
private const val ContributorSwitchMillis = 100L

private val contributorFrames = intArrayOf(
    R.drawable.contributor_01,
    R.drawable.contributor_02,
    R.drawable.contributor_03,
    R.drawable.contributor_04,
    R.drawable.contributor_05,
    R.drawable.contributor_06,
    R.drawable.contributor_07,
    R.drawable.contributor_08,
    R.drawable.contributor_09,
    R.drawable.contributor_10,
    R.drawable.contributor_11,
)

/**
 * 圆形头像面：裁剪 + 与关于页横幅同款的双遍描边。
 *
 * 描边参数逐项照抄 `About.kt` 横幅（Gray α0.1 + Gray α0.5 Overlay、`Stroke(12f)` 原始像素）：
 * 12f 的一半落在圆外被 Offscreen 裁剪吃掉，可见约 2dp，与头图观感一致。
 * 描边是静态的——不要给它加随换图闪动的高光。
 * 红线：不要抽成公共 modifier，也不要改 [yos.music.player.ui.widgets.basic.ShadowImage] 那套。
 */
private fun Modifier.avatarFace(): Modifier = this
    .size(AvatarSize)
    .graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        clip = true
        shape = CircleShape
    }
    .drawWithCache {
        onDrawWithContent {
            drawContent()
            val outline = CircleShape.createOutline(size, layoutDirection, this)
            drawOutline(
                outline = outline,
                color = Color.Gray.copy(alpha = 0.1f),
                style = Stroke(width = 12f)
            )
            drawOutline(
                outline = outline,
                color = Color.Gray.copy(alpha = 0.5f),
                style = Stroke(width = 12f),
                blendMode = BlendMode.Overlay
            )
        }
    }

/** 开发者头像（静态）。 */
@Composable
fun XiaoZhiAvatar(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(id = R.drawable.avatar_xiaozhi),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.avatarFace()
    )
}

/**
 * 贡献者头像：随机轮播贡献者图（每 [ContributorSwitchMillis] 一张），直接切换。
 *
 * 11 张图进组合时一次性在 IO 线程解码成 [ImageBitmap]：每 0.1s 重新 decode 会在主线程掉帧，
 * 预解码后单张 256² ≈ 256KB，十一张常驻约 2.8MB，对一行设置项可以接受。
 */
@Composable
fun ContributorAvatar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var frames by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var index by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        frames = withContext(Dispatchers.IO) {
            contributorFrames.map { res ->
                runCatching {
                    BitmapFactory.decodeResource(context.resources, res)?.asImageBitmap()
                }.getOrNull()
            }.filterNotNull()
        }
    }

    LaunchedEffect(frames) {
        if (frames.size < 2) return@LaunchedEffect
        while (true) {
            delay(ContributorSwitchMillis)
            // 在除当前下标以外的集合里等概率取一个，避免连续两次同一张
            val current = index
            var next = Random.nextInt(frames.size - 1)
            if (next >= current) next += 1
            index = next
        }
    }

    Box(modifier.avatarFace()) {
        val bitmap = frames.getOrNull(index)
        if (bitmap == null) {
            // 解码完成前先铺一层中性底，别让描边悬空
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
            )
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
