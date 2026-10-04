package yos.music.player.ui.widgets.basic

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DialogContent(text: String, modifier: Modifier = Modifier) =
    Text(text = text, modifier = modifier.alpha(0.5f), lineHeight = 20.sp, fontSize = 14.5.sp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionDialog(
    icon: @Composable () -> Unit,
    title: String,
    subTitle: String? = null,
    content: (@Composable () -> Unit)?,
    positiveContent: String,
    negativeContent: String? = null,
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties(),
    cornerRadius: (() -> Dp)? = null,
    destructive: Boolean = false,
    onPositive: () -> Unit,
    onNegative: (() -> Unit)? = null,
    onDismissRequest: () -> Unit
) = SonifyDialog(
    title = title,
    message = subTitle,
    content = content,
    positiveContent = positiveContent,
    negativeContent = negativeContent,
    onPositive = onPositive,
    onNegative = onNegative,
    onDismissRequest = onDismissRequest,
    cornerRadius = cornerRadius?.invoke(),
    destructive = destructive,
    dismissOnBackPress = false
)
