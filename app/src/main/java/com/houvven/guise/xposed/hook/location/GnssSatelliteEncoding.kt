package com.houvven.guise.xposed.hook.location

import android.location.GnssStatus
import android.os.Build

/** Android 11 增加基带信噪比标志后，SVID 和星座位段均向高位移了 4 bit。 */
internal fun packGpsSatelliteId(svid: Int, sdkInt: Int): Int {
    val svidShift = if (sdkInt >= Build.VERSION_CODES.R) 12 else 8
    val constellationShift = if (sdkInt >= Build.VERSION_CODES.R) 8 else 4
    // 星历、历书、参与定位置位；载波频率和基带信噪比缺失，不设置相应标志。
    return (svid shl svidShift) or (GnssStatus.CONSTELLATION_GPS shl constellationShift) or 0b111
}
