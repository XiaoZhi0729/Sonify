package yos.music.player.ui.widgets.basic

import android.annotation.SuppressLint
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import yos.music.player.ui.theme.YosRoundedCornerShape

private val defaultCorner = 9.dp
private val defaultPadding = 16.5.dp

@Composable
fun RoundColumn(
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier.padding(horizontal = defaultPadding),
    shape: Shape = YosRoundedCornerShape(defaultCorner),
    backgroundColor: Color = MaterialTheme.colorScheme.onSecondary,
    content: @Composable () -> Unit
) = /*Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors().copy(contentColor = contentColor, containerColor = backgroundColor),
        border = null
    ) {
        content()
    }*/

    Column(
        modifier = modifier.graphicsLayer {
            this.shape = shape
            this.clip = true
        }.background(backgroundColor),
    ) {
        content()
    }


fun LazyListScope.yosRoundColumn(content: RoundColumnLazyListScope.() -> Unit) {
    content.invoke(RoundColumnLazyListScope(this))
}

class RoundColumnLazyListScope(private val lazyListScope: LazyListScope) : LazyListScope {

    @ExperimentalFoundationApi
    override fun stickyHeader(
        key: Any?,
        contentType: Any?,
        content: @Composable (LazyItemScope.() -> Unit)
    ) {
        lazyListScope.stickyHeader(key, contentType) {
            RoundColumn(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }

    override fun item(
        key: Any?,
        contentType: Any?,
        content: @Composable (LazyItemScope.() -> Unit)
    ) {
        lazyListScope.item(key, contentType) {
            RoundColumn(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable (LazyItemScope.(index: Int) -> Unit)
    ) {
        lazyListScope.items(count, key, contentType) {
            when {

                count == 1 -> {
                    RoundColumn(modifier = Modifier.fillMaxWidth()) {
                        itemContent(this, it)
                    }
                }

                it == 0 -> {
                    RoundColumn(
                        shape = YosRoundedCornerShape(
                            topStart = defaultCorner,
                            topEnd = defaultCorner
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = defaultPadding)
                    ) {
                        itemContent(this, it)
                    }
                }

                it == (count - 1) -> {
                    RoundColumn(
                        shape = YosRoundedCornerShape(
                            bottomStart = defaultCorner,
                            bottomEnd = defaultCorner
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = defaultPadding)
                    ) {
                        itemContent(this, it)
                    }
                }

                else -> {
                    RoundColumn(
                        shape = RectangleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = defaultPadding)
                    ) {
                        itemContent(this, it)
                    }
                }

            }
        }
    }

}