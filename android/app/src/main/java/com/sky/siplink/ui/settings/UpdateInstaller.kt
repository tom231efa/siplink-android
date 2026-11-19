package com.sky.siplink.ui.settings

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import java.io.File

/**
 * Hands a downloaded update to the system's installer, which asks the user once more. Android
 * lets an app do so only once the user allowed it to install apps ("install unknown apps");
 * until then the user is taken to that setting, and the installer follows when they are back.
 */
internal object UpdateInstaller {
    /** Whether the system lets the app hand it packages to install. */
    fun allowed(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system setting that lets the app install packages; false when there is none to open. */
    fun openPermission(context: Context): Boolean = runCatching {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    /** Starts the system's installer for [path]; false when it could not be started. */
    fun install(context: Context, path: String): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", File(path))
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess
}
