/*
 * SPDX-FileCopyrightText: 2026 OverDrive
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

// OverDriveInstallerContractTest.kt — OverDrive Play Store always installs through OverDrive
package com.aurora.store.data.installer

import com.aurora.store.data.model.Installer
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class OverDriveInstallerContractTest {

    @Test
    fun storedInstallerOrdinalsStayStable() {
        // PREFERENCE_INSTALLER_ID stores the ordinal: OVERDRIVE must be appended, never inserted.
        assertThat(Installer.entries.map { it.name }).containsExactly(
            "SESSION", "NATIVE", "ROOT", "SERVICE", "AM", "SHIZUKU", "MICROG", "OVERDRIVE"
        ).inOrder()
    }

    @Test
    fun installerIsAlwaysOverDrive() {
        val appInstaller = source("data/installer/AppInstaller.kt")
        assertThat(appInstaller).contains(
            "fun getCurrentInstaller(context: Context): Installer = Installer.OVERDRIVE"
        )
        assertThat(appInstaller).contains("Installer.OVERDRIVE -> overDriveInstaller")
        assertThat(appInstaller).contains("OverDriveInstaller.uninstall(context, packageName)")
        assertThat(source("viewmodel/onboarding/OnboardingViewModel.kt"))
            .contains("save(PREFERENCE_INSTALLER_ID, Installer.OVERDRIVE.ordinal)")
    }

    @Test
    fun noUnknownSourcesPermissionAndNoSelfUpdate() {
        val permissions = source("data/providers/PermissionProvider.kt")
        assertThat(permissions).contains("PermissionType.INSTALL_UNKNOWN_APPS -> true")
        assertThat(permissions).doesNotContain("type = PermissionType.INSTALL_UNKNOWN_APPS")
        assertThat(source("util/PackageUtil.kt"))
            .contains("if (BuildConfig.APPLICATION_ID.startsWith(\"com.overdrive.\")) return false")
    }

    @Test
    fun splitSetsGoThroughInstallMultiple() {
        val installer = source("data/installer/OverDriveInstaller.kt")
        assertThat(installer).contains("service.installMultiple(")
        assertThat(installer).contains("minLevel = API_LEVEL_MULTIPLE")
    }

    private fun source(path: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val file = File(dir, "app/src/main/java/com/aurora/store/$path")
            if (file.isFile) return file.readText()
            val local = File(dir, "src/main/java/com/aurora/store/$path")
            if (local.isFile) return local.readText()
            dir = dir.parentFile
        }
        throw AssertionError("Could not locate $path")
    }
}
