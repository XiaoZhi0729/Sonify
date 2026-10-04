package yos.music.player.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

class UpdateDownloader(context: Context, client: OkHttpClient = UpdateTransport.defaultClient()) {
    private val context = context.applicationContext
    private val directory = File(this.context.filesDir, "updates").canonicalFile
    private val state = states.getOrPut(directory.path) { DirectoryState() }
    private val transport = UpdateTransport(client)

    suspend fun download(manifest: UpdateManifest, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            state.mutex.withLock {
                active {
                    val coroutine = currentCoroutineContext()
                    val checkActive = { coroutine.ensureActive() }
                    validateDevice(manifest)
                    if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update directory")
                    val complete = apkFile(manifest.versionCode)
                    state.verified.remove(complete.path)
                    val partial = File(directory, "update-${manifest.versionCode}.part")
                    val record = recordFile(manifest.versionCode)
                    val recordPartial = File(directory, "update-${manifest.versionCode}.json.part")
                    var committed = false
                    try {
                        if (complete.isFile) {
                            try {
                                verifyBlocking(complete, manifest, checkActive)
                                record.writeText(manifest.toJson(), Charsets.UTF_8)
                                onProgress(manifest.apkSize, manifest.apkSize)
                                rememberVerified(complete, manifest)
                                committed = true
                                return@active complete
                            } catch (failure: Exception) {
                                if (failure is kotlinx.coroutines.CancellationException) throw failure
                                checkActive()
                                complete.delete()
                                record.delete()
                            }
                        }
                        partial.delete()
                        transport.read(manifest.apkUrl.toHttpUrl(), manifest = false) { response, networkActive ->
                            val body = response.body ?: throw IOException("Empty update APK")
                            require(body.contentLength() == -1L || body.contentLength() == manifest.apkSize) {
                                "Unexpected APK content length"
                            }
                            body.byteStream().use { input ->
                                partial.outputStream().use { output ->
                                    val buffer = ByteArray(64 * 1024)
                                    var received = 0L
                                    onProgress(0L, manifest.apkSize)
                                    while (true) {
                                        networkActive()
                                        val count = input.read(buffer)
                                        if (count == -1) break
                                        received += count
                                        require(received <= manifest.apkSize && received <= UpdateManifest.MAX_APK_BYTES) {
                                            "APK exceeds declared size"
                                        }
                                        output.write(buffer, 0, count)
                                        onProgress(received, manifest.apkSize)
                                    }
                                    require(received == manifest.apkSize) { "Incomplete update APK" }
                                    output.fd.sync()
                                }
                            }
                        }
                        verifyBlocking(partial, manifest, checkActive)
                        recordPartial.writeText(manifest.toJson(), Charsets.UTF_8)
                        checkActive()
                        if (!partial.renameTo(complete)) throw IOException("Cannot commit update APK")
                        if (!recordPartial.renameTo(record)) throw IOException("Cannot commit update manifest")
                        verifyBlocking(complete, manifest, checkActive)
                        rememberVerified(complete, manifest)
                        committed = true
                        complete
                    } finally {
                        partial.delete()
                        recordPartial.delete()
                        if (!committed) {
                            complete.delete()
                            record.delete()
                        }
                    }
                }
            }
        }

    suspend fun verify(file: File, manifest: UpdateManifest): Unit = withContext(Dispatchers.IO) {
        state.mutex.withLock {
            active {
                val coroutine = currentCoroutineContext()
                val canonical = file.canonicalFile
                state.verified.remove(canonical.path)
                require(canonical == apkFile(manifest.versionCode)) { "Unexpected update file" }
                verifyBlocking(file, manifest) { coroutine.ensureActive() }
                rememberVerified(file, manifest)
            }
        }
    }

    /** Invoke during startup, before launching downloads. No file is touched during an active operation. */
    fun cleanup(currentVersion: Long) {
        require(currentVersion >= 0)
        synchronized(state) {
            if (state.active != 0) return
            directory.listFiles()?.forEach { file ->
                val match = Regex("update-([0-9]+)\\.(apk|part|json|json\\.part)").matchEntire(file.name)
                    ?: return@forEach
                val version = match.groupValues[1].toLongOrNull() ?: return@forEach
                val extension = match.groupValues[2]
                if (extension.endsWith("part") || version <= currentVersion ||
                    (extension == "json" && !apkFile(version).isFile)) {
                    state.verified.remove(file.path)
                    file.delete()
                }
            }
        }
    }

    internal fun verifyForInstall(file: File): File = synchronized(state) {
        check(state.active == 0) { "An update operation is active" }
        val canonical = file.canonicalFile
        val stamp = state.verified.remove(canonical.path)
            ?: throw IllegalStateException("Verify the update immediately before installation")
        require(canonical.parentFile == directory && canonical.name == "update-${stamp.version}.apk")
        require(canonical.isFile && canonical.length() == stamp.size && canonical.lastModified() == stamp.modified &&
            System.nanoTime() - stamp.verifiedAt <= 60_000_000_000L) { "Update changed or verification expired" }
        require(stamp.version > installedVersion(context)) { "Update would downgrade or reinstall" }
        canonical
    }

    private fun rememberVerified(file: File, manifest: UpdateManifest) = synchronized(state) {
        state.verified[file.canonicalPath] = VerifiedFile(manifest.versionCode, file.length(), file.lastModified(), System.nanoTime())
    }

    private suspend fun <T> active(action: suspend () -> T): T {
        synchronized(state) { state.active++ }
        try { return action() } finally { synchronized(state) { state.active-- } }
    }

    private fun apkFile(version: Long) = File(directory, "update-$version.apk")
    private fun recordFile(version: Long) = File(directory, "update-$version.json")

    private fun validateDevice(manifest: UpdateManifest) {
        manifest.validate()
        require(manifest.applicationId == context.packageName) { "Update belongs to another application" }
        require(manifest.versionCode > installedVersion(context)) { "Update would downgrade or reinstall" }
        require(Build.VERSION.SDK_INT >= manifest.minSdk) { "Android version is incompatible" }
        require(manifest.abis.any { it in Build.SUPPORTED_ABIS }) { "Device ABI is incompatible" }
    }

    @Suppress("DEPRECATION")
    private fun verifyBlocking(file: File, manifest: UpdateManifest, checkActive: () -> Unit = {}) {
        checkActive()
        validateDevice(manifest)
        verifyHash(file, manifest, checkActive)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IllegalArgumentException("Invalid update APK")
        require(archive.packageName == context.packageName) { "APK application ID mismatch" }
        require(version(archive) == manifest.versionCode && version(archive) > version(installed)) { "APK version mismatch" }
        if (Build.VERSION.SDK_INT >= 24) {
            val minimum = archive.applicationInfo?.minSdkVersion ?: throw IllegalArgumentException("Missing APK metadata")
            require(minimum == manifest.minSdk && minimum <= Build.VERSION.SDK_INT) { "APK minimum SDK mismatch" }
        }
        require(sameSigners(installed, archive)) { "APK signing identity mismatch" }
        ZipFile(file).use { zip ->
            val nativeAbis = mutableSetOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                checkActive()
                val entry = entries.nextElement()
                val parts = entry.name.split('/')
                if (parts.size == 3 && parts[0] == "lib" && parts[2].endsWith(".so")) nativeAbis += parts[1]
            }
            require(nativeAbis.isEmpty() ||
                (nativeAbis.all { it in manifest.abis } && nativeAbis.any { it in Build.SUPPORTED_ABIS })) {
                "APK native ABI mismatch"
            }
        }
        checkActive()
    }

    @Suppress("DEPRECATION")
    private fun sameSigners(installed: PackageInfo, archive: PackageInfo): Boolean {
        val current = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        val candidate = if (Build.VERSION.SDK_INT >= 28) archive.signingInfo?.apkContentsSigners else archive.signatures
        // Conservative identity match: key rotation requires an explicit future policy change.
        return !current.isNullOrEmpty() && !candidate.isNullOrEmpty() &&
            current.size == candidate.size && current.toSet() == candidate.toSet()
    }

    private data class VerifiedFile(val version: Long, val size: Long, val modified: Long, val verifiedAt: Long)

    private class DirectoryState {
        val mutex = Mutex()
        val verified = ConcurrentHashMap<String, VerifiedFile>()
        var active = 0
    }

    companion object {
        private val states = ConcurrentHashMap<String, DirectoryState>()

        @Suppress("DEPRECATION")
        private fun version(info: PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

        fun installedVersion(context: Context): Long =
            version(context.packageManager.getPackageInfo(context.packageName, 0))
    }
}

internal fun verifyHash(file: File, manifest: UpdateManifest, checkActive: () -> Unit = {}) {
    require(file.isFile && file.length() == manifest.apkSize && file.length() <= UpdateManifest.MAX_APK_BYTES) {
        "APK size mismatch"
    }
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkActive()
            val count = input.read(buffer)
            if (count == -1) break
            total += count
            require(total <= manifest.apkSize) { "APK grew during verification" }
            digest.update(buffer, 0, count)
        }
        require(total == manifest.apkSize) { "APK changed during verification" }
    }
    val expected = ByteArray(32) { index -> manifest.sha256.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    require(MessageDigest.isEqual(digest.digest(), expected)) { "APK SHA-256 mismatch" }
    checkActive()
}
