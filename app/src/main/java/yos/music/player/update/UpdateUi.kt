package yos.music.player.update

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
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
import yos.music.player.ui.widgets.basic.SonifyDialog
import yos.music.player.ui.widgets.basic.hasSonifyDialog
import java.util.Locale

@Composable
fun UpdateSettingsItems(developerModeEnabled: Boolean = false) {
    val context = LocalContext.current
    val owner = remember(context) { context.activity() as ViewModelStoreOwner }
    val model = remember(owner) { ViewModelProvider(owner)[UpdateViewModel::class.java] }
    var showFakeUpdate by remember { mutableStateOf(false) }
    LabelItem(
        title = stringResource(R.string.update_check),
        desc = if (model.stage == UpdateStage.Checking) stringResource(R.string.update_checking) else null,
        onClick = { model.check(manual = true) },
        onLongClick = if (developerModeEnabled) {
            { showFakeUpdate = true }
        } else null
    )
    SwitchItem(
        title = stringResource(R.string.update_auto_check),
        checkedLambda = { model.autoCheck },
        onClick = { model.changeAutoCheck(!model.autoCheck) }
    )
    if (showFakeUpdate) {
        FakeUpdateDialog(onDismiss = { showFakeUpdate = false })
    }
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
        tertiaryContent = if (stage == UpdateStage.Available) stringResource(R.string.update_ignore) else null,
        onTertiary = { model.ignore() },
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
            val foreground = LocalContentColor.current
            Column(Modifier.fillMaxWidth()) {
                if (stage == UpdateStage.Error) {
                    Text(
                        stringResource(errorText),
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = foreground.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (update != null) Spacer(Modifier.height(16.dp))
                }
                if (update != null) {
                    Text(
                        stringResource(R.string.update_version, update.versionName),
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val metadata = listOfNotNull(
                        update.publishedAt.substringBefore('T').takeIf { it.isNotBlank() },
                        size.takeIf { it.isNotBlank() }
                    ).joinToString("  ·  ")
                    Spacer(Modifier.height(6.dp))
                    Text(
                        metadata,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = foreground.copy(alpha = 0.55f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(18.dp))
                    HorizontalDivider(color = foreground.copy(alpha = 0.1f))
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.update_release_notes),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = foreground.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(10.dp))
                    @Suppress("DEPRECATION")
                    val locale = if (Build.VERSION.SDK_INT >= 24) context.resources.configuration.locales[0]
                        else context.resources.configuration.locale
                    val notes = update.notesFor(locale ?: Locale.ENGLISH)
                    if (notes.isEmpty()) {
                        Text(stringResource(R.string.update_notes_empty), fontSize = 14.sp, color = foreground.copy(alpha = 0.65f))
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            notes.forEach { note ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                                    Text("\u2022", fontSize = 14.sp, lineHeight = 21.sp, color = foreground.copy(alpha = 0.4f))
                                    Text(note, fontSize = 14.sp, lineHeight = 21.sp, color = foreground.copy(alpha = 0.85f), modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    if (stage == UpdateStage.Downloading || stage == UpdateStage.Verifying) {
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(progress = { model.progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(R.string.update_progress, (model.progress * 100).toInt()),
                            fontSize = 12.sp,
                            color = foreground.copy(alpha = 0.6f),
                            modifier = Modifier.align(Alignment.End)
                        )
                    }
                    if (stage == UpdateStage.InstallerOpened) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.update_installer_opened), fontSize = 14.sp, color = foreground.copy(alpha = 0.65f))
                    }
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
