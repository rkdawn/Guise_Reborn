package com.houvven.guise.ui.utils

import android.content.Context
import com.houvven.guise.db.TemplateDBHelper
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
        // user templates); fall back to fully random identity when none exist.
        val template = TemplateDBHelper.templateDao.getAll()
            .asSequence()
            .mapNotNull { runCatching { ModuleConfig.fromJson(it.configuration) }.getOrNull() }
            .filter { it.brand.isNotBlank() || it.model.isNotBlank() }
            .toList()
            .randomOrNull()
        val android = PresetRepository.get(context).androidVersions
            .filter { it.value.substringAfter('|').toIntOrNull()?.let { api -> api >= 29 } == true }
            .random()
        val carrier = CarrierPresetRepository.get(context).random()
        RandomSelection(
            template = template,
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

        val tplBrand = values.template?.brand.orEmpty()
        val tplDevice = values.template?.device.orEmpty()
        val tplProduct = values.template?.product.orEmpty()

        brand.value = tplBrand
        manufacturer.value = values.template?.manufacturer.orEmpty().ifBlank { tplBrand }
        model.value = values.template?.model.orEmpty()
        device.value = tplDevice
        product.value = tplProduct
        board.value = values.template?.board.orEmpty()
        hardware.value = values.template?.hardware.orEmpty()
        marketname.value = values.template?.marketname.orEmpty()
        displayId.value = values.template?.displayId.orEmpty()
        characteristics.value = values.template?.characteristics.orEmpty()
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
    val android: String,
    val carrier: CarrierPreset,
)
