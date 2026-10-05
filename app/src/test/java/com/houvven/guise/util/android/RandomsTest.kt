package com.houvven.guise.util.android

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomsTest {

    @Test
    fun generatedIdentifiersUsePlatformShapes() {
        repeat(100) {
            assertTrue(Randoms.randomMacAddress().matches(Regex("[0-9a-f]{2}(:[0-9a-f]{2}){5}")))
            assertTrue(Randoms.randomAndroidId().matches(Regex("[0-9a-f]{16}")))
            assertTrue(isValidImei(Randoms.randomIMEI()))
        }
    }

    @Test
    fun generatedCoordinatesStayInsideGlobalBounds() {
        repeat(1_000) {
            val (latitude, longitude) = Randoms.randomCoordinates()
            assertTrue(latitude in -90.0..90.0)
            assertTrue(longitude in -180.0..180.0)
        }
    }

    @Test
    fun fingerprintKeepsConfiguredIdentityCoherent() {
        val fingerprint = Randoms.randomFingerprint(
            brand = "Xiaomi",
            product = "houji",
            device = "houji",
            androidVersion = "16",
            buildId = "AP3A.250101.001",
        )

        assertTrue(fingerprint.startsWith("Xiaomi/houji/houji:16/AP3A.250101.001/"))
        assertTrue(fingerprint.endsWith(":user/release-keys"))
    }

    @Test
    fun buildIdTracksTheSelectedAndroidGeneration() {
        assertTrue(Randoms.randomBuildId("14").startsWith("UP1A."))
        assertTrue(Randoms.randomBuildId("15").startsWith("AP3A."))
        assertTrue(Randoms.randomBuildId("16").startsWith("BP2A."))
        assertTrue(Randoms.randomBuildId("17").startsWith("CP1A."))
    }

    @Test
    fun securityPatchRejectsImpossibleCalendarDates() {
        listOf("250231", "260431", "250229", "259999").forEach { date ->
            val patch = Randoms.securityPatchFromBuildId("BP2A.$date.003")
            LocalDate.parse(patch)
        }
    }

    @Test
    fun securityPatchPreservesValidLeapDay() {
        assertEquals("2024-02-29", Randoms.securityPatchFromBuildId("AP3A.240229.003"))
        assertEquals("2025-06-30", Randoms.securityPatchFromBuildId("BP2A.250630.003"))
    }

    private fun isValidImei(value: String): Boolean {
        if (!value.matches(Regex("\\d{15}"))) return false
        val sum = value.mapIndexed { index, char ->
            val digit = char.digitToInt()
            if (index % 2 == 1) (digit * 2).let { if (it > 9) it - 9 else it } else digit
        }.sum()
        return sum % 10 == 0
    }
}
