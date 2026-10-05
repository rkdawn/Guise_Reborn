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
        // 从完整目录里挑选匹配条目，不先随机品牌，也不在合并前二次抽取机型。
        val catalogPick = runCatching { DeviceCatalogRepository.get(context) }
            .getOrNull()
            ?.let { RandomDeviceIdentity.pickCatalogDevice(template, it) }
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

    val identity = RandomDeviceIdentity.from(
        template = values.template,
        catalogBrand = values.catalogBrand,
        catalogModel = values.catalogModel,
        androidPreset = values.android,
    )

    state.run {
        brand.value = identity.brand
        manufacturer.value = identity.manufacturer
        model.value = identity.model
        device.value = identity.device
        product.value = identity.product
        board.value = identity.board
        hardware.value = identity.hardware
        marketname.value = identity.marketname
        characteristics.value = identity.characteristics
        androidVersion.value = identity.androidVersion
        sdkInt.value = identity.sdkInt
        buildId.value = identity.buildId
        incremental.value = identity.incremental
        securityPatch.value = identity.securityPatch
        fingerPrint.value = identity.fingerPrint
        displayId.value = values.template?.displayId?.takeIf(String::isNotBlank)
            ?: identity.buildId

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
