package yos.music.player.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

class UpdateInstaller(context: Context) {
    private val context = context.applicationContext

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent = if (Build.VERSION.SDK_INT >= 26) {
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
    } else {
        Intent(Settings.ACTION_SECURITY_SETTINGS)
    }

    /** Call downloader.verify(file, manifest) on IO immediately before constructing this intent. */
    fun installIntent(file: File): Intent {
        val verified = UpdateDownloader(context).verifyForInstall(file)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", verified)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("Sonify update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
