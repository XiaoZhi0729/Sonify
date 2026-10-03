package yos.music.player.ui.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import yos.music.player.ui.UI
import yos.music.player.ui.pages.settings.Settings
import yos.music.player.ui.pages.settings.account.KugouLogin
import yos.music.player.ui.pages.settings.account.KugouVipSignIn
import yos.music.player.ui.pages.settings.audio.exoPlayer.ExoPlayerSettings
import yos.music.player.ui.pages.settings.audio.exoPlayer.MediaCodec
import yos.music.player.ui.pages.settings.audio.onlineQuality.OnlineQualitySettings
import yos.music.player.ui.pages.settings.extend.statusBarLyric.LyricGetter
import yos.music.player.ui.pages.settings.library.LibraryOverview
import yos.music.player.ui.pages.settings.others.About
import yos.music.player.ui.pages.settings.others.Acknowledgements
import yos.music.player.ui.pages.settings.performance.LyricSetting
import yos.music.player.ui.pages.settings.performance.NotificationSetting
import yos.music.player.ui.pages.settings.performance.userinterface.UserInterfaceSetting

fun NavGraphBuilder.settingsGraph(
    navController: NavHostController,
    onOpenLibrarySongs: () -> Unit
) {
    composable(UI.Settings.Main) { Settings(navController) }
    composable(UI.Settings.LibraryOverview) {
        LibraryOverview(navController = navController, onOpenLibrarySongs = onOpenLibrarySongs)
    }
    composable(UI.Settings.LyricGetter) { LyricGetter(navController) }
    composable(UI.Settings.ExoplayerSetting) { ExoPlayerSettings(navController) }
    composable(UI.Settings.OnlineQualitySetting) { OnlineQualitySettings(navController) }
    composable(UI.Settings.About) { About(navController) }
    composable(UI.Settings.Acknowledgements) { Acknowledgements(navController) }
    composable(UI.Settings.MediaCodec) { MediaCodec(navController) }
    composable(UI.Settings.LyricSetting) { LyricSetting(navController) }
    composable(UI.Settings.UserInterfaceSetting) { UserInterfaceSetting(navController) }
    composable(UI.Settings.NotificationSetting) { NotificationSetting(navController) }
    composable(UI.Settings.KugouLogin) { KugouLogin(navController) }
    composable(UI.Settings.KugouVipSignIn) { KugouVipSignIn(navController) }
    composable("poc_debug") { }
}
