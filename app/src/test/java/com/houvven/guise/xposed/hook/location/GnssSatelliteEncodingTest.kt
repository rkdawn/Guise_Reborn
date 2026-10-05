package com.houvven.guise.xposed.hook.location

import android.location.GnssStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GnssSatelliteEncodingTest {
    private fun svid(packed: Int, sdkInt: Int): Int =
        packed shr (if (sdkInt >= 30) 12 else 8)

    private fun constellation(packed: Int, sdkInt: Int): Int =
        (packed shr (if (sdkInt >= 30) 8 else 4)) and 0xf

    @Test
    fun modernLayoutKeepsPrnAndConstellationReadable() {
        val packed = packGpsSatelliteId(5, sdkInt = 30)
        assertEquals(5, svid(packed, 30))
        assertEquals(GnssStatus.CONSTELLATION_GPS, constellation(packed, 30))
    }

    @Test
    fun legacyLayoutIsReadableOnAndroid10() {
        val packed = packGpsSatelliteId(5, sdkInt = 29)
        assertEquals(5, svid(packed, 29))
        assertEquals(GnssStatus.CONSTELLATION_GPS, constellation(packed, 29))
    }

    @Test
    fun flagBitsMatchTheDecoderMask() {
        val packed = packGpsSatelliteId(3, sdkInt = 34)
        // 星历、历书、参与定位；载波频率与基带信噪比保持未设置。
        assertEquals(0b00111, packed and 0xff)
    }

    @Test
    fun bothLayoutsStayDistinctSoARegressionIsVisible() {
        assertEquals(false, packGpsSatelliteId(1, 29) == packGpsSatelliteId(1, 30))
    }
}
