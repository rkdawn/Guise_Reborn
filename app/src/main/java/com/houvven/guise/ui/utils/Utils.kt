package com.houvven.guise.ui.utils

import android.content.Context
import com.houvven.guise.db.TemplateDBHelper
import com.houvven.guise.device.DeviceBrand
import com.houvven.guise.device.DeviceCatalogRepository
import com.houvven.guise.device.DeviceModel
import com.houvven.guise.module.preset.CarrierPreset
import com.houvven.guise.module.preset.CarrierPresetRepository
import com.houvven.guise.module.preset.PresetRepository
import com.houvven.guise.module.preset.TimeZonePresetRepository
import com.houvven.guise.util.android.Randoms
import com.houvven.guise.xposed.config.ModuleConfig
import com.houvven.guise.xposed.config.ModuleConfigState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun oneClickRandom(state: ModuleConfigState, context: Context) {
    val values = withContext(Dispatchers.IO) {
        // Prefer a real device profile from the template library (bundled +
        // user templates); fall back to a real model from the device catalog so the
        // generated identity stays coherent, then to plain randomness.
        val template = TemplateDBHelper.templateDao.getAll()
            .asSequence()
            .mapNotNull { runCatching { ModuleConfig.fromJson(it.configuration) }.getOrNull() }
            .filter { it.brand.isNotBlank() || it.model.isNotBlank() }
            .toList()
            .randomOrNull()
        // Only consulted when the template library is empty (or lacks a given field).
        val catalogPick = runCatching { DeviceCatalogRepository.get(context) }
            .getOrNull()
            ?.brands
            .orEmpty()
            .flatMap { brand -> brand.models.map { model -> brand to model } }
            .randomOrNull()
        val android = PresetRepository.get(context).androidVersions
            .filter { it.value.substringAfter('|').toIntOrNull()?.let { api -> api >= 29 } == true }
            .random()
        val carrier = CarrierPresetRepository.get(context).random()
        RandomSelection(
            template = template,
            catalogBrand = catalogPick?.first,
            catalogModel = catalogPick?.second,
            android = android.value,
            carrier = carrier,
        )
    }

    state.run {
        val version = values.template?.androidVersion?.takeIf(String::isNotBlank)
            ?: values.android.substringBefore('|')
        val api = values.template?.sdkInt?.takeIf { it > 0 }?.toString()
            ?: values.android.substringAfter('|')
        val resolvedBuildId = values.template?.buildId?.takeIf(String::isNotBlank)
            ?: Randoms.randomBuildId(version)
        val resolvedIncremental = values.template?.incremental?.takeIf(String::isNotBlank)
            ?: Randoms.randomIncremental()

        // Each field falls back independently: a template may predate a field that was
        // added later (marketname / displayId / incremental / …), and blanking those was
        // the old behaviour — it produced an identity with empty Build fields, which is
        // worse than plain randomness rather than a "fully random identity".
        val catalogBrand = values.catalogBrand
        val catalogModel = values.catalogModel
        val tplBrand = values.template?.brand?.takeIf(String::isNotBlank)
            ?: catalogBrand?.buildBrand.orEmpty()
        val tplDevice = values.template?.device?.takeIf(String::isNotBlank)
            ?: catalogModel?.resolvedDevice().orEmpty()
        val tplProduct = values.template?.product?.takeIf(String::isNotBlank)
            ?: tplDevice

        brand.value = tplBrand
        manufacturer.value = values.template?.manufacturer?.takeIf(String::isNotBlank)
            ?: catalogBrand?.manufacturer.orEmpty().ifBlank { tplBrand }
        model.value = values.template?.model?.takeIf(String::isNotBlank)
            ?: catalogModel?.model.orEmpty()
        device.value = tplDevice
        product.value = tplProduct
        board.value = values.template?.board?.takeIf(String::isNotBlank) ?: tplDevice
        hardware.value = values.template?.hardware?.takeIf(String::isNotBlank) ?: tplDevice
        marketname.value = values.template?.marketname?.takeIf(String::isNotBlank)
            ?: catalogModel?.name.orEmpty()
        displayId.value = values.template?.displayId?.takeIf(String::isNotBlank)
            ?: resolvedBuildId
        characteristics.value = values.template?.characteristics?.takeIf(String::isNotBlank)
            ?: "default"
        androidVersion.value = version
        sdkInt.value = api
        buildId.value = resolvedBuildId
        incremental.value = resolvedIncremental
        securityPatch.value = values.template?.securityPatch?.takeIf(String::isNotBlank)
            ?: Randoms.securityPatchFromBuildId(resolvedBuildId)
        fingerPrint.value = values.template?.fingerPrint?.takeIf(String::isNotBlank)
            ?: Randoms.randomFingerprint(
                brand = tplBrand,
                product = tplProduct,
                device = tplDevice,
                androidVersion = version,
                buildId = resolvedBuildId,
                incremental = resolvedIncremental,
            )

        networkType.value = PresetRepository.get(context).networks.random().value

        wifiSSID.value = Randoms.randomString(10)
        wifiBSSID.value = Randoms.randomMacAddress()
        wifiMacAddress.value = Randoms.randomMacAddress()

        Randoms.randomCoordinates().let { (lat, lon) ->
            latitude.value = lat.toString()
            longitude.value = lon.toString()
        }

        simOperatorName.value = values.carrier.name
        simOperator.value = values.carrier.plmn
        simCountry.value = values.carrier.countryCode

        androidId.value = Randoms.randomAndroidId()
        imei.value = Randoms.randomIMEI()
        phoneNum.value = Randoms.randomPhoneNum()

        batteryLevel.value = Randoms.randomBatteryLevel().toString()
        timeZone.value = TimeZonePresetRepository.randomId()
    }
}

private data class RandomSelection(
    val template: ModuleConfig?,
    /** 设备目录中随机选中的品牌，仅当模板库无可用档案时作为兜底。 */
    val catalogBrand: DeviceBrand?,
    /** 设备目录中随机选中的机型，与 [catalogBrand] 配对。 */
    val catalogModel: DeviceModel?,
    val android: String,
    val carrier: CarrierPreset,
)
