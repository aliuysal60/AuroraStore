/*
 * SPDX-FileCopyrightText: 2026 OverDrive
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// OverDriveInstaller.kt — OverDrive Play Store's only installer: silent installs through OverDrive's ADB shell
package com.aurora.store.data.installer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import com.aurora.store.AuroraApp
import com.aurora.store.R
import com.aurora.store.data.event.InstallerEvent
import com.aurora.store.data.installer.base.InstallerBase
import com.aurora.store.data.model.Installer
import com.aurora.store.data.model.InstallerInfo
import com.aurora.store.data.room.download.Download
import com.aurora.store.util.PackageUtil
import com.aurora.store.util.PathUtil
import com.aurora.store.util.Preferences
import com.aurora.store.util.Preferences.PREFERENCE_AUTO_DELETE
import com.overdrive.app.store.IStoreInstallCallback
import com.overdrive.app.store.IStoreInstaller
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands the downloaded APKs to OverDrive, which streams them to the package manager in its ADB
 * shell — one file like `adb install -r -d`, a split set like `adb install-multiple -r -d` — so
 * the user never sees an install prompt (the head unit blocks Android's own installer).
 * Uninstall goes the same way (`pm uninstall`).
 *
 * OverDrive accepts calls only from its stores signed with its own key (signature permission
 * `com.overdrive.app.permission.STORE_INSTALL`). Each operation binds, makes one call, waits
 * for OverDrive's single answer and unbinds. Work runs on one background thread: callers may
 * be on any thread, and OverDrive installs one package at a time anyway.
 */
@Singleton
class OverDriveInstaller @Inject constructor(
    @ApplicationContext private val context: Context
) : InstallerBase(context) {

    companion object {
        private const val TAG = "OverDriveInstaller"
        const val OVERDRIVE_PACKAGE = "com.overdrive.app"
        private const val INSTALL_SERVICE = "com.overdrive.app.store.StoreInstallService"
        private const val API_VERSION = 1
        private const val API_LEVEL_MULTIPLE = 2
        private const val BIND_TIMEOUT_S = 30L

        // OverDrive answers within its own deadlines (copy + pm, up to ~11 min for a split set);
        // this only guards against an OverDrive process that dies without answering.
        private const val RESULT_TIMEOUT_MIN = 20L

        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, TAG).apply { isDaemon = true }
        }

        val installerInfo: InstallerInfo
            get() = InstallerInfo(
                id = 7,
                installer = Installer.OVERDRIVE,
                installerPackageNames = listOf(OVERDRIVE_PACKAGE),
                title = R.string.pref_install_mode_overdrive,
                subtitle = R.string.overdrive_installer_subtitle,
                description = R.string.overdrive_installer_desc
            )

        fun hasOverDrive(context: Context): Boolean =
            PackageUtil.isInstalled(context, OVERDRIVE_PACKAGE)

        /** Uninstall [packageName] through OverDrive (`pm uninstall`), without a system dialog. */
        fun uninstall(context: Context, packageName: String) {
            val app = context.applicationContext
            executor.execute {
                val result = call(app, minLevel = 0) { service, callback ->
                    service.uninstall(packageName, callback)
                }
                if (result.success) {
                    AuroraApp.events.send(InstallerEvent.Uninstalled(packageName))
                } else {
                    Log.w(TAG, "uninstall $packageName failed: ${result.message}")
                    AuroraApp.events.send(
                        InstallerEvent.Failed(packageName, error = result.message)
                    )
                }
            }
        }

        private class Result(val success: Boolean, val message: String)

        /**
         * Blocking (call on [executor]): bind OverDrive's install service, check its interface
         * level, make one call, wait for its answer, always unbind.
         */
        private fun call(
            context: Context,
            minLevel: Int,
            block: (IStoreInstaller, IStoreInstallCallback) -> Unit
        ): Result {
            val binder = AtomicReference<IBinder?>()
            val bound = CountDownLatch(1)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    binder.set(service)
                    bound.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {}

                override fun onBindingDied(name: ComponentName?) {
                    bound.countDown()
                }

                override fun onNullBinding(name: ComponentName?) {
                    bound.countDown()
                }
            }
            val intent = Intent().setClassName(OVERDRIVE_PACKAGE, INSTALL_SERVICE)
            val isBound = try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                Log.w(TAG, "OverDrive refused the binding: ${e.message}")
                false
            }
            try {
                if (!isBound) {
                    return Result(false, "OverDrive is not installed or refused the binding")
                }
                if (!bound.await(BIND_TIMEOUT_S, TimeUnit.SECONDS)) {
                    return Result(false, "OverDrive did not answer the binding")
                }
                val service = binder.get()?.let { IStoreInstaller.Stub.asInterface(it) }
                    ?: return Result(false, "OverDrive did not answer the binding")
                if (service.apiVersion() != API_VERSION) {
                    return Result(false, "OverDrive install API ${service.apiVersion()}")
                }
                // An OverDrive without apiLevel() answers 0 for it.
                if (service.apiLevel() < minLevel) {
                    return Result(false, "Update OverDrive to install this app")
                }
                val answer = AtomicReference(Result(false, "OverDrive did not report a result"))
                val answered = CountDownLatch(1)
                block(
                    service,
                    object : IStoreInstallCallback.Stub() {
                        override fun onResult(success: Boolean, message: String?) {
                            answer.set(Result(success, message.orEmpty()))
                            answered.countDown()
                        }
                    }
                )
                answered.await(RESULT_TIMEOUT_MIN, TimeUnit.MINUTES)
                return answer.get()
            } catch (e: RemoteException) {
                return Result(false, "OverDrive went away (${e.message})")
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result(false, "interrupted")
            } finally {
                try {
                    context.unbindService(connection)
                } catch (ignored: IllegalArgumentException) {
                    // Not bound: bindService threw before registering the connection.
                }
            }
        }

        private fun File.sha256Hex(): String {
            val digest = MessageDigest.getInstance("SHA-256")
            inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    override fun install(download: Download) {
        super.install(download)
        if (isAlreadyQueued(download.packageName)) {
            Log.i(TAG, "${download.packageName} already queued")
            return
        }
        AuroraApp.enqueuedInstalls.add(download.packageName)
        executor.execute {
            try {
                installDownload(download)
            } finally {
                removeFromInstallQueue(download.packageName)
            }
        }
    }

    private fun installDownload(download: Download) {
        // Shared libraries are separate packages and go first, like the root installer does.
        download.sharedLibs.forEach { lib ->
            if (!PackageUtil.isSharedLibraryInstalled(context, lib.packageName, lib.versionCode)) {
                val files = getFiles(download.packageName, download.versionCode, lib.packageName)
                val result = installFiles(lib.packageName, files)
                if (!result.success) {
                    postError(
                        download.packageName,
                        context.getString(R.string.installer_status_failure),
                        "${lib.packageName}: ${result.message}"
                    )
                    return
                }
            }
        }

        val result = installFiles(
            download.packageName,
            getFiles(download.packageName, download.versionCode)
        )
        if (result.success) {
            AuroraApp.events.send(InstallerEvent.Installed(download.packageName))
            // Per download, not InstallerBase.onInstallationSuccess(): that reads the shared
            // `download` field, which a later install() may already have replaced.
            notifyInstallation(context, download.displayName, download.packageName)
            if (Preferences.getBoolean(context, PREFERENCE_AUTO_DELETE)) {
                PathUtil.getAppDownloadDir(context, download.packageName, download.versionCode)
                    .deleteRecursively()
            }
        } else {
            postError(
                download.packageName,
                context.getString(R.string.installer_status_failure),
                result.message
            )
        }
    }

    /** Blocking: send [files] (one APK or a split set) to OverDrive and wait for its answer. */
    private fun installFiles(packageName: String, files: List<File>): Result {
        if (files.isEmpty()) return Result(false, "no APK downloaded")
        val hashes = files.map { it.sha256Hex() }
        val descriptors = files.map {
            ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        try {
            // Binder duplicates the descriptors when the call is sent, so ours close after it.
            return if (files.size == 1) {
                call(context, minLevel = 0) { service, callback ->
                    service.install(descriptors[0], hashes[0], packageName, callback)
                }
            } else {
                call(context, minLevel = API_LEVEL_MULTIPLE) { service, callback ->
                    service.installMultiple(
                        descriptors.toTypedArray(),
                        hashes.toTypedArray(),
                        packageName,
                        callback
                    )
                }
            }
        } finally {
            descriptors.forEach { runCatching { it.close() } }
        }
    }
}
