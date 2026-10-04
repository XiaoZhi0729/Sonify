package yos.music.player.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

internal class PageDataViewModel : ViewModel() {
    private val values = mutableMapOf<String, MutableState<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <T> state(key: String, initialValue: () -> T): MutableState<T> =
        values.getOrPut(key) { mutableStateOf(initialValue()) } as MutableState<T>
}

// Keep loaded rows with their back-stack entry so a restored scroll anchor is not
// clamped against an empty list or just the first page during navigation back.
@Composable
internal fun <T> rememberPageData(key: String, initialValue: () -> T): MutableState<T> {
    val holder: PageDataViewModel = viewModel()
    return holder.state(key, initialValue)
}
