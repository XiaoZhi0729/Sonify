package yos.music.player.ui.pages.settings.performance.userinterface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.withNight

@Composable
internal fun ScreenCornerCalibrationDialog(
    cornerRadius: () -> Dp,
    icon: @Composable () -> Unit,
    title: String,
    subTitle: String,
    content: @Composable () -> Unit,
    positiveContent: String,
    onPositive: () -> Unit,
    onDismissRequest: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            // Keep the outer edge at the display; system-bar padding belongs inside the shell.
            Surface(
                modifier = Modifier.fillMaxWidth().padding(start = 5.dp, end = 5.dp, bottom = 5.dp),
                shape = YosRoundedCornerShape(cornerRadius()),
                color = Color.White withNight Color.Black,
                contentColor = Color.Black withNight Color.White
            ) {
                Column(
                    Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(26.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    icon()
                    Text(title, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
                    Text(subTitle, fontSize = 16.sp, lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(20.dp))
                    content()
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onPositive, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) {
                        Text(positiveContent, color = Color.White, fontSize = 16.5.sp)
                    }
                }
            }
        }
    }
}
