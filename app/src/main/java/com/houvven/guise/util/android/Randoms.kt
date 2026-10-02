package com.houvven.guise.util.android

import java.util.UUID
import java.util.Locale
import kotlin.math.round
import kotlin.random.Random


object Randoms {

    private const val ALPHANUMERIC =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    private const val HEX = "0123456789abcdef"

    /**
     * 生成一个指定长度的随机字符串
     * @param length 长度
     * @return 随机字符串
     */
    fun randomString(length: Int): String {
        require(length >= 0)
        return buildString(length) {
            repeat(length) { append(ALPHANUMERIC.random()) }
        }
    }

    /**
     * 生成一个随机长度的随机字符串 长度在[10, 100]内
     */
    fun randomString(): String {
        return randomString(Random.nextInt(10, 101))
    }


    fun uuid(): String {
        return UUID.randomUUID().toString()
    }

    /**
     * 生成一个不带横线的UUID
     */
    fun uuidNoDash(): String {
        return uuid().replace("-", "")
    }

    /**
     * 随机生成Mac地址
     */
    fun randomMacAddress(): String {
        val octets = IntArray(6) { Random.nextInt(256) }
        // Locally administered unicast address: valid for spoofing without claiming a vendor OUI.
        octets[0] = (octets[0] or 0x02) and 0xFE
        return octets.joinToString(":") { it.toString(16).padStart(2, '0') }
    }


    /**
     * 随机生成IMEI
     */
    fun randomIMEI(): String {
        val body = randomDigits(14)
        return body + imeiCheckDigit(body)
    }

    /** Android ID/SSAID is conventionally exposed as 16 lower-case hexadecimal characters. */
    fun randomAndroidId(): String = buildString(16) {
        repeat(16) { append(HEX.random()) }
    }

    fun randomPhoneNum(): String {
        return "1${randomDigits(10)}"
    }

    fun randomBuildId(androidVersion: String = ""): String {
        val major = androidVersion.substringBefore('.').toIntOrNull()
        val (prefix, firstYear) = when (major) {
            10 -> "QP1A" to 19
            11 -> "RP1A" to 20
            12 -> "SP1A" to 21
            13 -> "TP1A" to 22
            14 -> "UP1A" to 23
            15 -> "AP3A" to 24
            16 -> "BP2A" to 25
            17 -> "CP1A" to 26
            else -> "GU1A" to 24
        }
        return String.format(
            Locale.ROOT,
            "$prefix.%02d%02d%02d.%03d",
            Random.nextInt(firstYear, firstYear + 2),
            Random.nextInt(1, 13),
            Random.nextInt(1, 29),
            Random.nextInt(1_000),
        )
    }

    fun randomFingerprint(
        brand: String,
        product: String,
        device: String,
        androidVersion: String,
        buildId: String,
        incremental: String = randomIncremental(),
    ): String {
        val safeBrand = brand.fingerprintPart("generic")
        val safeDevice = device.fingerprintPart("device")
        val safeProduct = product.fingerprintPart(safeDevice)
        val safeRelease = androidVersion.fingerprintPart("16")
        val safeBuildId = buildId.fingerprintPart(randomBuildId(androidVersion))
        val safeIncremental = incremental.fingerprintPart(randomIncremental())
        return "$safeBrand/$safeProduct/$safeDevice:$safeRelease/$safeBuildId/" +
            "$safeIncremental:user/release-keys"
    }

    /** INCREMENTAL is conventionally a short digits-only build sequence. */
    fun randomIncremental(): String = randomDigits(7)

    /**
     * Derives a plausible security patch date (yyyy-mm-dd) from a build id of the
     * form "BP2A.250816.003" (YYMMDD segment). Falls back to a recent-ish date.
     */
    fun securityPatchFromBuildId(buildId: String): String {
        val match = Regex("\\.(\\d{2})(\\d{2})(\\d{2})\\.").find(buildId)
        return if (match != null) {
            val (yy, mm, dd) = match.destructured
            "20$yy-$mm-$dd"
        } else {
            String.format(
                Locale.ROOT,
                "202%d-%02d-%02d",
                Random.nextInt(4, 7),
                Random.nextInt(1, 13),
                Random.nextInt(1, 29),
            )
        }
    }

    fun randomBatteryLevel(): Int = Random.nextInt(101)

    fun randomCoordinates(): Pair<Double, Double> {
        val latitude = round(Random.nextDouble(-90.0, 90.0) * 1_000_000) / 1_000_000
        val longitude = round(Random.nextDouble(-180.0, 180.0) * 1_000_000) / 1_000_000
        return latitude to longitude
    }

    private fun randomDigits(length: Int): String = buildString(length) {
        repeat(length) { append(Random.nextInt(10)) }
    }

    private fun imeiCheckDigit(body: String): Int {
        require(body.length == 14 && body.all(Char::isDigit))
        val sum = body.mapIndexed { index, char ->
            val digit = char.digitToInt()
            if (index % 2 == 1) (digit * 2).let { if (it > 9) it - 9 else it } else digit
        }.sum()
        return (10 - sum % 10) % 10
    }

    private fun String.fingerprintPart(fallback: String): String =
        trim().takeIf(String::isNotEmpty)
            ?.replace(Regex("[\\s/:]+"), "_")
            ?: fallback

}
