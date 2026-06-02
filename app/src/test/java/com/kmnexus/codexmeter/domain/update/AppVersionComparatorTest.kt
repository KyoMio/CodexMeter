package com.kmnexus.codexmeter.domain.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionComparatorTest {
    @Test
    fun `remote patch version is newer than debug current version`() {
        assertTrue(AppVersionComparator.isRemoteVersionNewer(currentVersionName = "0.1.0-debug", remoteVersionName = "v0.1.1"))
    }

    @Test
    fun `same semantic version is not newer even when current has debug suffix`() {
        assertFalse(AppVersionComparator.isRemoteVersionNewer(currentVersionName = "0.1.0-debug", remoteVersionName = "v0.1.0"))
    }

    @Test
    fun `remote minor version wins over current patch version`() {
        assertTrue(AppVersionComparator.isRemoteVersionNewer(currentVersionName = "0.1.9", remoteVersionName = "0.2.0"))
    }

    @Test
    fun `invalid remote version is not treated as newer`() {
        assertFalse(AppVersionComparator.isRemoteVersionNewer(currentVersionName = "0.1.0", remoteVersionName = "latest"))
    }
}
