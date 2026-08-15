package com.nuanke.focus.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersioningTest {
    @Test fun `detects newer semantic version`() = assertTrue(Versioning.isNewer("v1.2.0", "1.1.9"))
    @Test fun `does not treat equal padded version as newer`() = assertFalse(Versioning.isNewer("1.2", "1.2.0"))
    @Test fun `ignores prerelease suffix for numeric comparison`() = assertTrue(Versioning.isNewer("2.0.0-beta", "1.9.9"))
}

