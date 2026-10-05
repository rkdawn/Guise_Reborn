package com.houvven.guise.ui.utils

import com.houvven.guise.device.DeviceBrand
import com.houvven.guise.device.DeviceCatalog
import com.houvven.guise.device.DeviceModel
import com.houvven.guise.util.android.Randoms
import com.houvven.guise.xposed.config.ModuleConfig

/**
 * 一键随机的设备身份，按以下优先级补齐字段：
 *  1. 模板显式填写的字段；
 *  2. 模板指纹中可解析出的字段；
 *  3. 与模板品牌、型号和设备代号匹配的目录条目；
 *  4. 系统版本预设和随机值。
 */
internal data class RandomDeviceIdentity(
    val brand: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val product: String,
    val board: String,
    val hardware: String,
    val marketname: String,
    val characteristics: String,
    val androidVersion: String,
    val sdkInt: String,
    val buildId: String,
    val incremental: String,
    val securityPatch: String,
    val fingerPrint: String,
) {
    companion object {
        fun pickCatalogDevice(
            template: ModuleConfig?,
            catalog: DeviceCatalog,
        ): Pair<DeviceBrand, DeviceModel>? {
            val parsed = template?.fingerPrint?.let(ParsedFingerprint::parseFingerprint)
            return catalog.brands.flatMap { brand ->
                brand.models.filter { matchesTemplate(template, parsed, brand, it) }
                    .map { brand to it }
            }.randomOrNull()
        }

        fun from(
            template: ModuleConfig?,
            catalogBrand: DeviceBrand?,
            catalogModel: DeviceModel?,
            androidPreset: String,
        ): RandomDeviceIdentity {
            val fingerprint = template?.fingerPrint?.takeIf(String::isNotBlank)
            val parsed = fingerprint?.let(ParsedFingerprint::parseFingerprint)

            val version = template?.androidVersion?.takeIf(String::isNotBlank)
                ?: parsed?.androidVersion
                ?: androidPreset.substringBefore('|')
            // 预设只有与最终版本一致时才能提供 SDK，不能沿用另一个随机版本的 API。
            val sdk = template?.sdkInt?.takeIf { it > 0 }?.toString()
                ?: androidPreset.takeIf { version == it.substringBefore('|') }?.substringAfter('|')
                ?: versionToSdk(version)
                ?: androidPreset.substringAfter('|')
            val buildId = template?.buildId?.takeIf(String::isNotBlank)
                ?: parsed?.buildId
                ?: Randoms.randomBuildId(version)
            val incremental = template?.incremental?.takeIf(String::isNotBlank)
                ?: parsed?.incremental
                ?: Randoms.randomIncremental()

            // 在合并入口再次校验，不能依赖调用方恰好传来正确的品牌/机型组合。
            val matchingModel = catalogModel?.takeIf { model ->
                catalogBrand != null && model in catalogBrand.models &&
                    matchesTemplate(template, parsed, catalogBrand, model)
            }
            val matchingBrand = catalogBrand?.takeIf { matchingModel != null }
            val brand = template?.brand?.takeIf(String::isNotBlank)
                ?: parsed?.brand
                ?: matchingBrand?.buildBrand.orEmpty()
            val model = template?.model?.takeIf(String::isNotBlank)
                ?: matchingModel?.model.orEmpty()
            val device = template?.device?.takeIf(String::isNotBlank)
                ?: parsed?.device
                ?: matchingModel?.resolvedDevice()?.takeIf(String::isNotBlank)
                // 没有对应条目时使用本机型的标识，不借用另一台设备的代号。
                ?: model.replace(Regex("[\\s/:]+"), "_").ifBlank { "device" }
            val product = template?.product?.takeIf(String::isNotBlank)
                ?: parsed?.product
                ?: device
            val manufacturer = template?.manufacturer?.takeIf(String::isNotBlank)
                ?: matchingBrand?.manufacturer.orEmpty().ifBlank { brand }
            val resolvedFingerprint = if (
                fingerprint != null && parsed != null &&
                parsed.matches(brand, product, device, version, buildId, incremental)
            ) {
                fingerprint
            } else {
                Randoms.randomFingerprint(brand, product, device, version, buildId, incremental)
            }

            return RandomDeviceIdentity(
                brand = brand,
                manufacturer = manufacturer,
                model = model,
                device = device,
                product = product,
                board = template?.board?.takeIf(String::isNotBlank) ?: device,
                hardware = template?.hardware?.takeIf(String::isNotBlank) ?: device,
                marketname = template?.marketname?.takeIf(String::isNotBlank)
                    ?: matchingModel?.name.orEmpty(),
                characteristics = template?.characteristics?.takeIf(String::isNotBlank)
                    ?: "default",
                androidVersion = version,
                sdkInt = sdk,
                buildId = buildId,
                incremental = incremental,
                securityPatch = template?.securityPatch?.takeIf(String::isNotBlank)
                    ?: Randoms.securityPatchFromBuildId(buildId),
                fingerPrint = resolvedFingerprint,
            )
        }

        private fun matchesTemplate(
            template: ModuleConfig?,
            parsed: ParsedFingerprint?,
            brand: DeviceBrand,
            model: DeviceModel,
        ): Boolean {
            val templateBrand = template?.brand?.takeIf(String::isNotBlank) ?: parsed?.brand
            if (templateBrand != null && listOf(brand.key, brand.buildBrand, brand.displayName)
                    .none { it.equals(templateBrand.trim(), ignoreCase = true) }
            ) return false
            val templateModel = template?.model?.takeIf(String::isNotBlank)
            if (templateModel != null && listOf(model.model, model.name)
                    .none { it.equals(templateModel.trim(), ignoreCase = true) }
            ) return false
            val templateDevice = template?.device?.takeIf(String::isNotBlank) ?: parsed?.device
            return model.model.isNotBlank() && (templateDevice == null ||
                templateDevice.trim().equals(model.resolvedDevice(), ignoreCase = true))
        }

        /** Android 版本号 → 默认 API 级别（与 res/raw/presets.json 的映射一致）。 */
        private fun versionToSdk(version: String): String? = when (version) {
            "10" -> "29"
            "11" -> "30"
            "12" -> "31"
            "12L" -> "32"
            "13" -> "33"
            "14" -> "34"
            "15" -> "35"
            "16" -> "36"
            "17" -> "37"
            else -> null
        }
    }
}

/** 指纹形如 brand/product/device:version/buildId/incremental:variant。 */
internal data class ParsedFingerprint(
    val brand: String,
    val product: String,
    val device: String,
    val androidVersion: String,
    val buildId: String,
    val incremental: String,
) {
    fun matches(
        brand: String,
        product: String,
        device: String,
        androidVersion: String,
        buildId: String,
        incremental: String,
    ): Boolean = this.brand == brand && this.product == product && this.device == device &&
        this.androidVersion == androidVersion && this.buildId == buildId &&
        this.incremental == incremental

    companion object {
        private val fingerprintRegex =
            Regex("""^([\w.-]+)/([\w.-]+)/([\w.-]+):([^/]+)/([^/]+)/([^:]+):.+$""")

        fun parseFingerprint(value: String): ParsedFingerprint? {
            val match = fingerprintRegex.matchEntire(value.trim()) ?: return null
            val (brand, product, device, version, buildId, incremental) = match.destructured
            if (listOf(brand, product, device, version, buildId, incremental).any(String::isBlank)) {
                return null
            }
            return ParsedFingerprint(brand, product, device, version, buildId, incremental)
        }
    }
}
