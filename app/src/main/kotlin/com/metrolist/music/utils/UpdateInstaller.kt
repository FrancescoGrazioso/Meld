/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.io.OutputStream

enum class UpdateInstallFailure {
    PermissionRequired,
    Download,
    Install,
}

sealed interface UpdateInstallState {
    data object Idle : UpdateInstallState

    data class Downloading(
        val progress: Float,
    ) : UpdateInstallState

    data object Installing : UpdateInstallState

    data object AwaitingConfirmation : UpdateInstallState

    data class Failed(
        val reason: UpdateInstallFailure,
    ) : UpdateInstallState
}

/**
 * Downloads a release APK from GitHub and hands it to the system package installer.
 */
object UpdateInstaller {
    private const val UPDATE_DIR_NAME = "updates"
    private const val UPDATE_FILE_NAME = "update.apk"
    private const val SESSION_ENTRY_NAME = "update"
    private const val DOWNLOAD_TIMEOUT_MILLIS = 30 * 60 * 1000L

    // The engine default request timeout is far too short for an APK sized download
    private val client =
        HttpClient(CIO) {
            engine { requestTimeout = DOWNLOAD_TIMEOUT_MILLIS }
        }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null

    private val mutableState = MutableStateFlow<UpdateInstallState>(UpdateInstallState.Idle)
    val state: StateFlow<UpdateInstallState> = mutableState.asStateFlow()

    fun canInstallPackages(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            "package:${context.packageName}".toUri(),
        )

    fun reset() {
        mutableState.value = UpdateInstallState.Idle
    }

    /**
     * Starts the download on an app scoped coroutine so it survives screen changes.
     */
    fun start(
        context: Context,
        downloadUrl: String,
    ) {
        if (activeJob?.isActive == true) return

        val appContext = context.applicationContext
        activeJob = scope.launch { downloadAndInstall(appContext, downloadUrl) }
    }

    private suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
    ) {
        UpdateInstallReceiver.clearConfirmation(context)

        if (!canInstallPackages(context)) {
            mutableState.value = UpdateInstallState.Failed(UpdateInstallFailure.PermissionRequired)
            return
        }

        val apk =
            download(context, downloadUrl).getOrElse {
                reportFailure(context, UpdateInstallFailure.Download)
                return
            }

        mutableState.value = UpdateInstallState.Installing
        runCatching { commitInstallSession(context, apk) }
            .onFailure { reportFailure(context, UpdateInstallFailure.Install) }
    }

    private suspend fun download(
        context: Context,
        downloadUrl: String,
    ): Result<File> =
        runCatching {
            val target = prepareTargetFile(context)
            mutableState.value = UpdateInstallState.Downloading(0f)

            client.prepareGet(downloadUrl).execute { response ->
                val totalBytes = response.contentLength() ?: 0L
                response.bodyAsChannel().toInputStream().use { input ->
                    target.outputStream().use { output ->
                        copyReportingProgress(input, output, totalBytes) { progress ->
                            mutableState.value = UpdateInstallState.Downloading(progress)
                        }
                    }
                }
            }

            target
        }

    private fun commitInstallSession(
        context: Context,
        apk: File,
    ) {
        val packageInstaller = context.packageManager.packageInstaller
        val params =
            PackageInstaller
                .SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setAppPackageName(context.packageName) }
        val sessionId = packageInstaller.createSession(params)

        try {
            packageInstaller.openSession(sessionId).use { session ->
                session.openWrite(SESSION_ENTRY_NAME, 0, apk.length()).use { sessionStream ->
                    apk.inputStream().use { it.copyTo(sessionStream) }
                    session.fsync(sessionStream)
                }
                session.commit(statusIntentSender(context, sessionId))
            }
        } catch (throwable: Throwable) {
            packageInstaller.abandonSession(sessionId)
            throw throwable
        }
    }

    private fun statusIntentSender(
        context: Context,
        sessionId: Int,
    ): IntentSender {
        val intent =
            Intent(context, UpdateInstallReceiver::class.java)
                .setAction(UpdateInstallReceiver.ACTION_INSTALL_STATUS)

        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_MUTABLE
        }

        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }

    internal fun onAwaitingConfirmation() {
        mutableState.value = UpdateInstallState.AwaitingConfirmation
    }

    internal fun onInstallFailed(context: Context) {
        reportFailure(context, UpdateInstallFailure.Install)
    }

    internal fun onInstallSucceeded(context: Context) {
        discardDownloadedApk(context)
        mutableState.value = UpdateInstallState.Idle
    }

    private fun reportFailure(
        context: Context,
        reason: UpdateInstallFailure,
    ) {
        discardDownloadedApk(context)
        mutableState.value = UpdateInstallState.Failed(reason)
    }

    private fun updateApkFile(context: Context): File = File(File(context.cacheDir, UPDATE_DIR_NAME), UPDATE_FILE_NAME)

    private fun prepareTargetFile(context: Context): File {
        File(context.cacheDir, UPDATE_DIR_NAME).mkdirs()
        return updateApkFile(context).apply { delete() }
    }

    private fun discardDownloadedApk(context: Context) {
        updateApkFile(context).delete()
    }
}

private const val COPY_BUFFER_SIZE = 64 * 1024

/**
 * Copies the stream and reports progress once per whole percent, or never when the size is unknown.
 */
internal fun copyReportingProgress(
    input: InputStream,
    output: OutputStream,
    totalBytes: Long,
    onProgress: (Float) -> Unit,
) {
    val buffer = ByteArray(COPY_BUFFER_SIZE)
    var copiedBytes = 0L
    var reportedPercent = -1

    while (true) {
        val readBytes = input.read(buffer)
        if (readBytes < 0) break

        output.write(buffer, 0, readBytes)
        copiedBytes += readBytes

        if (totalBytes <= 0L) continue

        val percent = (copiedBytes * 100 / totalBytes).toInt()
        if (percent != reportedPercent) {
            reportedPercent = percent
            onProgress(percent / 100f)
        }
    }

    output.flush()
}
