package yos.music.player.ui.pages.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.R

@Composable
fun SmallLabelItem(icon: Painter, label: String, onClick: () -> Unit) =
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            modifier = Modifier
                .padding(start = 18.dp, end = 2.dp)
                .size(40.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(text = label, fontSize = 20.sp, modifier = Modifier.fillMaxWidth().weight(1f))
        Icon(
            painter = painterResource(id = R.drawable.ic_action_next), contentDescription = null,
            modifier = Modifier
                .padding(end = 21.dp)
                .height(12.dp)
                .alpha(0.3f), tint = MaterialTheme.colorScheme.onBackground
        )
    }