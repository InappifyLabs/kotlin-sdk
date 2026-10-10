package com.inappify.sdk.internal.platform

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build

internal class AppMetadata(
    internal val packageIdentifier: String,
    internal val versionName: String,
    internal val versionCode: Long,
)

internal fun interface AppMetadataProvider {
    fun get(): AppMetadata
}

internal class AndroidAppMetadataProvider(
    context: Context,
) : AppMetadataProvider {

    private val applicationContext: Context = context.applicationContext

    override fun get(): AppMetadata {
        val packageInfo = packageInfo()
        return AppMetadata(
            packageIdentifier = applicationContext.packageName,
            versionName = packageInfo.versionName.orEmpty(),
            versionCode = packageInfo.compatibleLongVersionCode(),
        )
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(): PackageInfo =
        applicationContext.packageManager.getPackageInfo(
            applicationContext.packageName,
            0,
        )

    @Suppress("DEPRECATION")
    private fun PackageInfo.compatibleLongVersionCode(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            versionCode.toLong()
        }
}
