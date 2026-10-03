package io.github.mangi.eta.data.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateCheckerTest {

    @Test
    fun newerPatchVersionIsNewer() {
        assertTrue(AppUpdateChecker.isNewer("3.0.7", "3.0.6"))
    }

    @Test
    fun sameVersionIsNotNewer() {
        assertFalse(AppUpdateChecker.isNewer("3.0.6", "3.0.6"))
    }

    @Test
    fun olderVersionIsNotNewer() {
        assertFalse(AppUpdateChecker.isNewer("3.0.5", "3.0.6"))
    }

    @Test
    fun missingSegmentsCountAsZero() {
        assertTrue(AppUpdateChecker.isNewer("3.1", "3.0.9"))
        assertFalse(AppUpdateChecker.isNewer("3.0", "3.0.1"))
    }

    @Test
    fun vPrefixIsIgnored() {
        assertTrue(AppUpdateChecker.isNewer("v3.1.0", "3.0.6"))
        assertFalse(AppUpdateChecker.isNewer("v3.0.6", "3.0.6"))
    }

    /** Heta 的版本号是四段式（3.0.6.x），上游只测了三段式 —— 这条补上，免得比较逻辑悄悄失效。 */
    @Test
    fun fourSegmentVersionsAreComparedCorrectly() {
        assertTrue(AppUpdateChecker.isNewer("3.0.6.39", "3.0.6.38"))
        assertFalse(AppUpdateChecker.isNewer("3.0.6.38", "3.0.6.38"))
        assertFalse(AppUpdateChecker.isNewer("3.0.6.37", "3.0.6.38"))
        assertFalse(AppUpdateChecker.isNewer("3.0.6", "3.0.6.1"))
        assertTrue(AppUpdateChecker.isNewer("3.0.7", "3.0.6.38"))
        assertTrue(AppUpdateChecker.isNewer("3.1.0", "3.0.6.38"))
    }
}
