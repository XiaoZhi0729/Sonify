package yos.music.player.update

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class UpdateStage { Idle, Checking, Current, Available, Downloading, Verifying, Ready, Permission, InstallerOpened, Error }
enum class UpdateFailure { Check, Download, Install, Permission }

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    var stage by mutableStateOf(UpdateStage.Idle)
        private set
    var manifest by mutableStateOf<UpdateManifest?>(null)
        private set
    var progress by mutableStateOf(0f)
        private set
    var visible by mutableStateOf(false)
        private set
    var pendingAutomaticPrompt by mutableStateOf(false)
        private set
    var failure by mutableStateOf(UpdateFailure.Check)
        private set
    var autoCheck by mutableStateOf(UpdateSettings.autoCheck)
        private set
    private val repository = UpdateRepository()
    private val downloader = UpdateDownloader(application)
    private var apk: File? = null
    private var operation: Job? = null
    private var manualRequest = false
    private var suppressPrompt = false
    private var autoAttempts = 0
    private var downloadGeneration = 0
    @Suppress("DEPRECATION")
    private val currentVersion: Long = application.packageManager
        .getPackageInfo(application.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
        }
    private val initialization = viewModelScope.launch(Dispatchers.IO) {
        downloader.cleanup(currentVersion)
    }

    fun changeAutoCheck(enabled: Boolean) {
        autoCheck = enabled
        UpdateSettings.autoCheck = enabled
        if (!enabled) pendingAutomaticPrompt = false
    }

    fun check(manual: Boolean) {
        if (operation?.isCompleted == false) {
            if (manual) { manualRequest = true; suppressPrompt = false; visible = true }
            return
        }
        if (stage in listOf(UpdateStage.Ready, UpdateStage.Permission, UpdateStage.InstallerOpened)) {
            if (manual) visible = true
            return
        }
        val now = System.currentTimeMillis()
        if (!manual && !UpdateCheckPolicy.shouldCheck(
                autoCheck, now, UpdateSettings.lastSuccessAt, UpdateSettings.lastFailureAt, autoAttempts
            )) return
        if (!manual) autoAttempts++
        manualRequest = manual
        suppressPrompt = false
        pendingAutomaticPrompt = false
        visible = manual
        stage = UpdateStage.Checking
        operation = viewModelScope.launch {
            try {
                initialization.join()
                val result = repository.check(currentVersion)
                UpdateSettings.lastSuccessAt = System.currentTimeMillis()
                UpdateSettings.lastFailureAt = 0L
                manifest = result
                stage = if (result == null) UpdateStage.Current else UpdateStage.Available
                visible = manualRequest && !suppressPrompt
                pendingAutomaticPrompt = !suppressPrompt && !manualRequest && autoCheck && result != null &&
                    result.versionCode != UpdateSettings.ignoredVersion
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                UpdateSettings.lastFailureAt = System.currentTimeMillis()
                failure = UpdateFailure.Check
                stage = UpdateStage.Error
                visible = manualRequest && !suppressPrompt            }
        }
    }

    fun dismiss() {
        visible = false
        pendingAutomaticPrompt = false
        suppressPrompt = true
    }

    fun showPendingAutomaticPrompt() {
        if (pendingAutomaticPrompt) {
            pendingAutomaticPrompt = false
            visible = true
        }
    }

    fun ignore() {
        manifest?.let { UpdateSettings.ignoredVersion = it.versionCode }
        dismiss()
    }

    fun download() {
        val update = manifest ?: return
        if (operation?.isCompleted == false) return
        pendingAutomaticPrompt = false
        visible = true
        stage = UpdateStage.Downloading
        progress = 0f
        val generation = ++downloadGeneration
        operation = viewModelScope.launch {
            try {
                initialization.join()
                var lastProgressAt = 0L
                apk = downloader.download(update) { received, total ->
                    val now = SystemClock.elapsedRealtime()
                    if (received == total || now - lastProgressAt >= 100) {
                        lastProgressAt = now
                        viewModelScope.launch {
                            if (generation == downloadGeneration && stage == UpdateStage.Downloading) {
                                progress = (received.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)
                                if (received == total) stage = UpdateStage.Verifying
                            }
                        }
                    }
                }
                stage = UpdateStage.Ready
            } catch (cancelled: CancellationException) {
                stage = UpdateStage.Available
                throw cancelled
            } catch (_: Exception) {
                failure = UpdateFailure.Download
                stage = UpdateStage.Error
            }
        }
    }

    fun cancelDownload() { visible = false; downloadGeneration++; operation?.cancel() }

    fun prepareInstall(onReady: (File) -> Unit) {
        val update = manifest ?: return
        val file = apk ?: return
        if (operation?.isCompleted == false) return
        stage = UpdateStage.Verifying
        operation = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { downloader.verify(file, update) }
                stage = UpdateStage.Ready
                onReady(file)
            } catch (cancelled: CancellationException) {
                stage = UpdateStage.Ready
                throw cancelled
            } catch (_: Exception) {
                apk = null
                failure = UpdateFailure.Download
                stage = UpdateStage.Error
            }
        }
    }

    fun waitingForPermission() { stage = UpdateStage.Permission }
    fun installerOpened() { stage = UpdateStage.InstallerOpened }
    fun installerReturned() {
        if (stage == UpdateStage.InstallerOpened) stage = UpdateStage.Ready
    }
    fun installFailed(permission: Boolean = false) {
        failure = if (permission) UpdateFailure.Permission else UpdateFailure.Install
        stage = UpdateStage.Error
        visible = true
    }
    fun retry() {
        when (failure) {
            UpdateFailure.Check -> check(manual = true)
            UpdateFailure.Download -> download()
            else -> stage = UpdateStage.Ready
        }
    }

}
