package yos.music.player.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import yos.music.player.R
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.SwitchItem
import yos.music.player.ui.pages.settings.startWeb
import yos.music.player.ui.widgets.basic.SonifyDialog
import yos.music.player.ui.widgets.basic.hasSonifyDialog
import java.util.Locale

@Composable
fun UpdateSettingsItems() {
    val context = LocalContext.current
    val owner = remember(context) { context.activity() as ViewModelStoreOwner }
    val model = remember(owner) { ViewModelProvider(owner)[UpdateViewModel::class.java] }
    LabelItem(
        title = stringResource(R.string.update_check),
        desc = if (model.stage == UpdateStage.Checking) stringResource(R.string.update_checking) else null,
        onClick = { model.check(manual = true) }
    )
    SwitchItem(
        title = stringResource(R.string.update_auto_check),
        checkedLambda = { model.autoCheck },
        onClick = { model.changeAutoCheck(!model.autoCheck) }
    )
}

@Composable
fun UpdateHost(model: UpdateViewModel, startupReady: Boolean) {
    val context = LocalContext.current
    val installer = remember(context) { UpdateInstaller(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var meteredConfirmation by remember { mutableStateOf(false) }
    val modalOpen = hasSonifyDialog()
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(startupReady, resumed, modalOpen, model.autoCheck) {
        if (startupReady && resumed && !modalOpen && !model.visible) {
            delay(2_000)
            model.check(manual = false)
        }
    }
    LaunchedEffect(startupReady, resumed, modalOpen, model.pendingAutomaticPrompt) {
        if (startupReady && resumed && !modalOpen && model.pendingAutomaticPrompt) {
            model.showPendingAutomaticPrompt()
        }
    }
    val installLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        model.installerReturned()
    }
    fun launchInstaller(file: java.io.File) {
        try {
            installLauncher.launch(installer.installIntent(file))
            model.installerOpened()
        } catch (_: Exception) { model.installFailed() }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (installer.canInstall()) model.prepareInstall { file -> launchInstaller(file) }
        else model.installFailed(permission = true)
    }
    fun install() {
        model.prepareInstall { file ->
            if (installer.canInstall()) launchInstaller(file)
            else {
                try {
                    model.waitingForPermission()
                    permissionLauncher.launch(installer.permissionIntent())
                } catch (_: Exception) { model.installFailed() }
            }
        }
    }
    if (!model.visible || !resumed) return
    val update = model.manifest
    val stage = model.stage
    val size = update?.let { Formatter.formatFileSize(context, it.apkSize) }.orEmpty()
    val errorText = when (model.failure) {
        UpdateFailure.Check -> R.string.update_check_failed
        UpdateFailure.Download -> R.string.update_download_failed
        UpdateFailure.Install -> R.string.update_install_failed
        UpdateFailure.Permission -> R.string.update_permission_denied
    }
    if (meteredConfirmation) {
        SonifyDialog(
            title = stringResource(R.string.update_metered_title),
            message = stringResource(R.string.update_metered_message, size),
            positiveContent = stringResource(R.string.update_download),
            negativeContent = stringResource(R.string.update_cancel),
            onPositive = { meteredConfirmation = false; model.download() },
            onDismissRequest = { meteredConfirmation = false },
            onNegative = { meteredConfirmation = false }
        )
        return
    }
    val busy = stage in listOf(UpdateStage.Checking, UpdateStage.Downloading, UpdateStage.Verifying, UpdateStage.Permission)
    val title = when (stage) {
        UpdateStage.Checking -> R.string.update_checking
        UpdateStage.Current -> R.string.update_up_to_date
        UpdateStage.Downloading -> R.string.update_downloading
        UpdateStage.Verifying -> R.string.update_verifying
        UpdateStage.Permission -> R.string.update_install_permission
        UpdateStage.Error -> R.string.update_error
        else -> R.string.update_available
    }
    val positive = when (stage) {
        UpdateStage.Available -> R.string.update_download
        UpdateStage.Ready, UpdateStage.InstallerOpened -> R.string.update_install
        UpdateStage.Error -> R.string.update_retry
        else -> R.string.common_ok
    }
    SonifyDialog(
        title = stringResource(title),
        positiveContent = stringResource(positive),
        positiveEnabled = !busy,
        closeOnPositive = false,
        negativeContent = stringResource(if (stage == UpdateStage.Downloading) R.string.update_cancel else R.string.update_later),
        negativeEnabled = stage != UpdateStage.Verifying,
        onNegative = {
            if (stage == UpdateStage.Downloading) model.cancelDownload() else model.dismiss()
        },
        onDismissRequest = { model.dismiss() },
        dismissEnabled = !busy,
        dismissOnBackPress = !busy,
        onPositive = {
            when (stage) {
                UpdateStage.Available -> {
                    val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    if (network.isActiveNetworkMetered) meteredConfirmation = true else model.download()
                }
                UpdateStage.Ready, UpdateStage.InstallerOpened -> install()
                UpdateStage.Error -> model.retry()
                else -> model.dismiss()
            }
        },
        content = {
            Column(Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                if (stage == UpdateStage.Error) Text(stringResource(errorText))
                if (update != null) {
                    Text(stringResource(R.string.update_version, update.versionName))
                    Text(stringResource(R.string.update_size, size))
                    if (update.publishedAt.isNotBlank()) Text(update.publishedAt.substringBefore('T'))
                    Spacer(Modifier.height(12.dp))
                    @Suppress("DEPRECATION")
                    val locale = if (Build.VERSION.SDK_INT >= 24) context.resources.configuration.locales[0]
                        else context.resources.configuration.locale
                    val notes = update.notesFor(locale ?: Locale.ENGLISH)
                    if (notes.isEmpty()) Text(stringResource(R.string.update_notes_empty))
                    else notes.forEach { Text(it); Spacer(Modifier.height(6.dp)) }
                    if (stage == UpdateStage.Downloading || stage == UpdateStage.Verifying) {
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.update_progress, (model.progress * 100).toInt()))
                    }
                    if (stage == UpdateStage.InstallerOpened) Text(stringResource(R.string.update_installer_opened))
                    if (stage == UpdateStage.Available) {
                        TextButton(onClick = { model.ignore() }) { Text(stringResource(R.string.update_ignore)) }
                    }
                }
                if (!busy) {
                    TextButton(onClick = {
                        startWeb(update?.releasePageUrl ?: "https://github.com/XiaoZhi0729/Sonify/releases", context)
                    }) { Text(stringResource(R.string.update_release_page)) }
                }
            }
        }
    )
}

private fun Context.activity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> error("Update settings require an Activity")
}
