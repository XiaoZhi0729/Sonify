package yos.music.player.ui.pages.settings.performance.userinterface

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.alexzhirkevich.cupertino.CupertinoSlider
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.DialogContent
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenCornerSetDialog(modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) return

    val cornerValue = remember("MainActivity_cornerValue") {
        mutableFloatStateOf(SettingsLibrary.ScreenCorner.toFloatOrNull()?.coerceIn(0f, 130f) ?: 30f)
    }
    val context = LocalContext.current

    ScreenCornerCalibrationDialog(
        cornerRadius = { cornerValue.floatValue.dp },
        icon = {
            Icon(painter = painterResource(id = R.drawable.ic_tips_roundcorner), contentDescription = null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(62.dp))
        },
        title = stringResource(id = R.string.tip_corner_title),
        subTitle = stringResource(id = R.string.tip_corner_subtitle),
        content = {
            val interactionSource = remember { MutableInteractionSource() }
            CupertinoTheme {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(
                            color = (Color.LightGray withNight Color.DarkGray).copy(
                                alpha = 0.15f
                            ),
                            shape = YosRoundedCornerShape(14.dp)
                        )
                        .padding(vertical = 15.dp, horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {

                    Icon(painter = painterResource(id = R.drawable.ic_tips_minus), contentDescription = null, modifier = Modifier
                        .size(12.dp)
                        .alpha(0.45f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (cornerValue.floatValue <= 0f) return@clickable
                                Vibrator.click(context)
                                cornerValue.floatValue -= 1f
                            }))
                    CupertinoSlider(value = cornerValue.floatValue, onValueChange = {
                        cornerValue.floatValue = it
                    }, thumb = {
                        Spacer(
                            Modifier
                                .size(23.dp)
                                .hoverable(interactionSource = interactionSource)
                                .shadow(
                                    8.dp,
                                    CircleShape,
                                    clip = false,
                                    spotColor = Color.Black.copy(alpha = 0.55f)
                                )
                                .background(Color.White, CircleShape)
                        )
                    }, interactionSource = interactionSource, modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(end = 8.dp, start = 12.dp),
                        valueRange = 0f..130f)
                    Icon(painter = painterResource(id = R.drawable.ic_tips_plus), contentDescription = null, modifier = Modifier
                        .padding(end = 2.dp)
                        .size(14.dp)
                        .alpha(0.45f)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (cornerValue.floatValue >= 130f) return@clickable
                                Vibrator.click(context)
                                cornerValue.floatValue += 1f
                            }))
                }
                DialogContent(text = stringResource(id = R.string.tip_corner_desc), modifier = Modifier.padding(top = 6.dp))
            }
        },
        positiveContent = stringResource(id = R.string.tip_corner_save),
        onPositive = {
            SettingsLibrary.ScreenCorner = cornerValue.floatValue.roundToInt().toString()
            SettingsLibrary.ScreenCornerSet = true
            onDismiss()
        }) {
        onDismiss()
    }
}