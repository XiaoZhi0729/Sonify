package yos.music.player.ui.pages.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.NonSkippableComposable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import yos.music.player.R
import yos.music.player.data.libraries.SettingsLibrary.LibraryListSortEnum
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.dropdown.LiquidGlassDropdownMenuItem
import yos.music.player.ui.widgets.dropdown.TitleBarGlassMenu
import yos.music.player.ui.widgets.mochi.AlphabetIndex
import yos.music.player.ui.widgets.mochi.IndexedListResult

@Immutable
data class LibraryMenuAction(
    val text: String,
    val onClick: () -> Unit,
)

/**
 * 资料库列表页统一的右上角「⋯」液态玻璃排序菜单。
 *
 * - 支持可选 [leadingAction]（如「我的在线歌单」顶部的「新建歌单」入口）；
 * - 排序维度包含「默认」「名称」及可选的「歌曲数量」，下方以细分割线隔开「升序 / 降序」。
 */
@Composable
fun LibrarySortMenuButton(
    selectedSort: () -> Int,
    onSelectSort: (Int) -> Unit,
    descending: () -> Boolean,
    onSelectDescending: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supportsSongCount: Boolean = true,
    leadingAction: LibraryMenuAction? = null,
) {
    val expanded = remember { mutableStateOf(false) }

    TitleBarGlassMenu(
        expanded = expanded.value,
        onExpand = { expanded.value = true },
        onDismiss = { expanded.value = false },
        triggerIcon = Icons.Rounded.MoreHoriz,
        modifier = modifier.padding(end = 10.dp),
    ) {
        LibrarySortMenuContent(
            selectedSort = selectedSort(),
            onSelectSort = onSelectSort,
            descending = descending(),
            onSelectDescending = onSelectDescending,
            supportsSongCount = supportsSongCount,
            leadingAction = leadingAction,
            onDismiss = { expanded.value = false },
        )
    }
}

@Composable
@NonSkippableComposable
private fun LibrarySortMenuContent(
    selectedSort: Int,
    onSelectSort: (Int) -> Unit,
    descending: Boolean,
    onSelectDescending: (Boolean) -> Unit,
    supportsSongCount: Boolean,
    leadingAction: LibraryMenuAction?,
    onDismiss: () -> Unit,
) {
    if (leadingAction != null) {
        LiquidGlassDropdownMenuItem(
            text = leadingAction.text,
            onClick = {
                onDismiss()
                leadingAction.onClick()
            },
        )
        LibrarySortMenuDivider()
    }

    LiquidGlassDropdownMenuItem(
        text = stringResource(id = R.string.list_sort_default),
        onClick = { onSelectSort(LibraryListSortEnum.DEFAULT.ordinal) },
        selected = selectedSort == LibraryListSortEnum.DEFAULT.ordinal,
    )
    LiquidGlassDropdownMenuItem(
        text = stringResource(id = R.string.normal_button_sort_by_name),
        onClick = { onSelectSort(LibraryListSortEnum.NAME.ordinal) },
        selected = selectedSort == LibraryListSortEnum.NAME.ordinal,
    )
    if (supportsSongCount) {
        LiquidGlassDropdownMenuItem(
            text = stringResource(id = R.string.list_sort_by_song_count),
            onClick = { onSelectSort(LibraryListSortEnum.SONG_COUNT.ordinal) },
            selected = selectedSort == LibraryListSortEnum.SONG_COUNT.ordinal,
        )
    }

    LibrarySortMenuDivider()

    LiquidGlassDropdownMenuItem(
        text = stringResource(id = R.string.normal_button_sort_ascending),
        onClick = { onSelectDescending(false) },
        selected = !descending,
    )
    LiquidGlassDropdownMenuItem(
        text = stringResource(id = R.string.normal_button_sort_descending),
        onClick = { onSelectDescending(true) },
        selected = descending,
    )
}

@Composable
private fun LibrarySortMenuDivider() =
    Spacer(
        modifier = Modifier
            .padding(start = 22.dp, end = 14.dp)
            .padding(vertical = 5.dp)
            .height(0.65.dp)
            .background((Color.Black withNight Color.White).copy(alpha = 0.1f)),
    )

/**
 * 详情页（在线歌单/本地&在线专辑）通用的排序菜单项，供 [TitleBarGlassMenu] 内容末尾复用。
 */
@Composable
@NonSkippableComposable
fun DetailSortMenuItems(
    sortOptions: List<Pair<Int, String>>,
    selectedSort: Int,
    onSelectSort: (Int) -> Unit,
    descending: Boolean,
    onSelectDescending: (Boolean) -> Unit,
    divider: @Composable () -> Unit = { LibrarySortMenuDivider() },
) {
    sortOptions.forEach { (ordinal, label) ->
        LiquidGlassDropdownMenuItem(
            text = label,
            onClick = { onSelectSort(ordinal) },
            selected = selectedSort == ordinal,
        )
    }
    divider()
    LiquidGlassDropdownMenuItem(
        text = stringResource(id = R.string.normal_button_sort_ascending),
        onClick = { onSelectDescending(false) },
        selected = !descending,
    )
    LiquidGlassDropdownMenuItem(
        text = stringResource(id = R.string.normal_button_sort_descending),
        onClick = { onSelectDescending(true) },
        selected = descending,
    )
}

/**
 * 对任意列表执行「按某名称键多语言排序 + 生成字母索引（搜索态或分组不足 2 时隐藏索引）」。
 */
fun <T> buildAlphabetIndexedItems(
    items: List<T>,
    descending: Boolean,
    isSearching: Boolean,
    keySelector: (T) -> String,
): IndexedListResult<T> {
    if (items.isEmpty()) return IndexedListResult(emptyList(), emptyList(), emptyList())
    val result = AlphabetIndex.build(
        items = items,
        descending = descending,
        nameSelector = keySelector,
    )
    return if (isSearching || result.labels.size < 2) {
        IndexedListResult(result.sortedItems, emptyList(), emptyList())
    } else {
        result
    }
}

/**
 * 统一的列表排序与字母索引生成函数：
 * 仅当 [sortOrdinal] == [LibraryListSortEnum.NAME] 且 [isSearching] == false 时计算字母索引，
 * 若去重后的首字母分组不足 2 个则返回空索引（隐藏索引条）。
 */
fun <T> sortAndIndexLibraryItems(
    items: List<T>,
    sortOrdinal: Int,
    descending: Boolean,
    isSearching: Boolean,
    nameSelector: (T) -> String,
    songCountSelector: ((T) -> Int)? = null,
): IndexedListResult<T> {
    if (items.isEmpty()) return IndexedListResult(emptyList(), emptyList(), emptyList())

    return when (sortOrdinal) {
        LibraryListSortEnum.NAME.ordinal -> {
            buildAlphabetIndexedItems(
                items = items,
                descending = descending,
                isSearching = isSearching,
                keySelector = nameSelector,
            )
        }

        LibraryListSortEnum.SONG_COUNT.ordinal -> {
            val comparator = compareBy<T> { songCountSelector?.invoke(it) ?: 0 }
                .thenBy { AlphabetIndex.sortKeyOf(nameSelector(it)) }
            val sorted = if (descending) {
                items.sortedWith(comparator.reversed())
            } else {
                items.sortedWith(comparator)
            }
            IndexedListResult(sorted, emptyList(), emptyList())
        }

        else -> {
            val sorted = if (descending) items.asReversed() else items
            IndexedListResult(sorted, emptyList(), emptyList())
        }
    }
}
