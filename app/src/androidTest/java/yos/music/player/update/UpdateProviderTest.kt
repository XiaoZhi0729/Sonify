package yos.music.player.update

import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UpdateProviderTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun updateFilesUseNarrowContentUriPath() {
        val file = File(context.filesDir, "updates/provider-test.apk")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        assertEquals("content", uri.scheme)
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        assertTrue(uri.path.orEmpty().startsWith("/update_apks/"))
    }

    @Test fun unrelatedPrivateFileCannotBeShared() {
        try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(context.filesDir, "private-secret.txt"))
            fail("Expected a restricted FileProvider path")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun unverifiedApkCannotCreateInstallIntent() {
        try {
            UpdateInstaller(context).installIntent(File(context.filesDir, "updates/update-2147483647.apk"))
            fail("Expected installation to require verification")
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun permissionActivityDoesNotStartNewTask() {
        val intent = UpdateInstaller(context).permissionIntent()
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            assertEquals(Uri.parse("package:${context.packageName}"), intent.data)
        }
    }
}
