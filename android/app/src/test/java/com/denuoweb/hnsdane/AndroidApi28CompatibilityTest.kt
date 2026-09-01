package com.denuoweb.hnsdane

import com.denuoweb.hnsdane.net.supportsDirectSslCertificateAccess
import com.denuoweb.hnsdane.ui.usesScopedDownloadStorage
import com.denuoweb.hnsdane.wallet.supportsTypedDataSyncForegroundService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidApi28CompatibilityTest {
    @Test
    fun api28SelectsEveryLegacyCompatibilityBoundary() {
        assertTrue(needsActivityCreatedThemeFallback(28))
        assertFalse(supportsDirectSslCertificateAccess(28))
        assertFalse(usesScopedDownloadStorage(28))
        assertFalse(supportsTypedDataSyncForegroundService(28))
    }

    @Test
    fun api29SelectsEveryModernCompatibilityBoundary() {
        assertFalse(needsActivityCreatedThemeFallback(29))
        assertTrue(supportsDirectSslCertificateAccess(29))
        assertTrue(usesScopedDownloadStorage(29))
        assertTrue(supportsTypedDataSyncForegroundService(29))
    }

    @Test
    fun activityRecreatesOnlyWhenEffectiveThemeChanges() {
        assertFalse(activityThemeNeedsRecreation(appliedDark = false, requestedDark = false))
        assertFalse(activityThemeNeedsRecreation(appliedDark = true, requestedDark = true))
        assertTrue(activityThemeNeedsRecreation(appliedDark = false, requestedDark = true))
        assertTrue(activityThemeNeedsRecreation(appliedDark = true, requestedDark = false))
    }
}
