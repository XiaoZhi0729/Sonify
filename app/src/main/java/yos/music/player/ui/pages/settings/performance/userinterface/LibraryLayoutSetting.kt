package yos.music.player.ui.pages.settings.performance.userinterface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.libraries.LibraryLayoutLogic
import yos.music.player.data.libraries.LibraryLayoutStore
import yos.music.player.data.libraries.LibrarySectionId
import yos.music.player.ui.navigation.popBackStackSafely
import yos.music.player.ui.pages.discovery.HomeEditDragHolder
import yos.music.player.ui.pages.discovery.SectionEditEntry
import yos.music.player.ui.pages.discovery.homeEditDragGesture
import yos.music.player.ui.pages.discovery.homeEditKey
import yos.music.player.ui.pages.discovery.sectionEditItems
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.GroupSpacerMedium
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.SonifyDialog
import yos.music.player.ui.widgets.basic.Title

/** 资料库板块标题。 */
@Composable
internal fun librarySectionTitle(id: LibrarySectionId): String = stringResource(
    when (id) {
        LibrarySectionId.HeroWidgets -> R.string.library_section_hero_widgets_title
        LibrarySectionId.RecentPlayed -> R.string.library_recent_played
        LibrarySectionId.FollowedArtists -> R.string.library_followed_artists
        LibrarySectionId.Collections -> R.string.library_collection_title
    }
)

/**
 * 自定义资料库：长按拖拽调整资料库板块顺序或跨组隐藏/恢复，改动即时写入 [LibraryLayoutStore]。
 */
@Composable
fun LibraryLayoutSetting(navController: NavController) {
    val context = LocalContext.current
    val layout by LibraryLayoutStore.sections
    val allKeys = remember(layout) { layout.map { homeEditKey(it.id) } }
    val isDefault = layout == LibraryLayoutLogic.default()

    val listState = rememberLazyListState()
    val dragHolder = remember { HomeEditDragHolder() }
    val resetAnimating = remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(resetAnimating.value) {
        if (resetAnimating.value) {
            delay(30L * LibrarySectionId.entries.size + 320L)
            resetAnimating.value = false
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(dragHolder.latched) {
        if (!dragHolder.latched && dragHolder.geometry != null) {
            delay(800)
            dragHolder.clear()
        }
    }

    SettingBackground {
        Box(
            Modifier
                .fillMaxSize()
                .homeEditDragGesture(
                    enabled = !resetAnimating.value,
                    listState = listState,
                    dragHolder = dragHolder,
                    allKeys = allKeys,
                    onCommit = { from, to ->
                        LibraryLayoutStore.update(
                            LibraryLayoutLogic.dragCommit(LibraryLayoutStore.sections.value, from, to)
                        )
                    }
                )
        ) {
            if (showResetConfirm) {
                SonifyDialog(
                    title = stringResource(id = R.string.library_edit_reset_title),
                    message = stringResource(id = R.string.home_edit_reset_message),
                    onDismissRequest = { showResetConfirm = false },
                    positiveContent = stringResource(id = R.string.home_edit_reset_confirm),
                    onPositive = {
                        showResetConfirm = false
                        Vibrator.click(context)
                        resetAnimating.value = true
                        LibraryLayoutStore.reset()
                    },
                    negativeContent = stringResource(id = R.string.common_cancel),
                    onNegative = { showResetConfirm = false },
                    dismissOnClickOutside = true,
                    dismissOnBackPress = true
                )
            }

            Title(
                title = stringResource(id = R.string.settings_performance_ui_library_title),
                onBack = { navController.popBackStackSafely() },
                titleHorizontalPadding = 34.5.dp,
                listState = listState,
            ) {
                item("top_spacer") {
                    GroupSpacerMedium()
                }

                sectionEditItems(
                    layout = layout.map { SectionEditEntry(it.id, it.visible) },
                    listState = listState,
                    dragHolder = dragHolder,
                    resetAnimating = resetAnimating.value,
                    titleFor = { id ->
                        LibrarySectionId.fromKey(id)?.let { librarySectionTitle(it) } ?: id
                    },
                    onToggle = { id ->
                        LibrarySectionId.fromKey(id)?.let {
                            Vibrator.click(context)
                            LibraryLayoutStore.toggle(it)
                        }
                    }
                )

                item("reset_footer") {
                    Column {
                        GroupSpacer()
                        RoundColumn {
                            LabelItem(
                                title = stringResource(id = R.string.bottom_bar_setting_reset),
                                superLink = true,
                                enabled = !isDefault && !dragHolder.active && !resetAnimating.value
                            ) {
                                Vibrator.click(context)
                                showResetConfirm = true
                            }
                        }
                        GroupSpacer()
                    }
                }
            }
        }
    }
}
