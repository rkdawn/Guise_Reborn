package com.houvven.guise.ui.utils

import com.houvven.guise.device.DeviceBrand
import com.houvven.guise.device.DeviceCatalog
import com.houvven.guise.device.DeviceModel
import com.houvven.guise.xposed.config.ModuleConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomDeviceIdentityTest {
    private val samsungBrand = DeviceBrand(
        key = "samsung",
        displayName = "Samsung",
        buildBrand = "samsung",
        manufacturer = "samsung",
        models = listOf(
            DeviceModel(model = "SM-S9480", name = "Galaxy S26 Ultra", alias = "m3q", type = "mob"),
        ),
    )
    private val googleBrand = DeviceBrand(
        key = "google",
        displayName = "Google",
        buildBrand = "google",
        manufacturer = "Google",
        models = listOf(
            DeviceModel(model = "G-2PW4100", name = "Pixel", alias = "sailfish", type = "mob"),
        ),
    )

    @Test
    fun mismatchedCatalogBrandNeverFillsTemplateFields() {
        // 模板选了三星，目录随机到 Google：任何字段都不允许来自目录机型。
        val identity = RandomDeviceIdentity.from(
            template = ModuleConfig(brand = "samsung", model = "SM-S9480"),
            catalogBrand = googleBrand,
            catalogModel = googleBrand.models.single(),
            androidPreset = "16|36",
        )
        assertEquals("samsung", identity.brand)
        assertEquals("SM-S9480", identity.model)
        assertTrue(identity.device.isNotBlank())
        assertFalse(identity.device.contains("sailfish"))
        assertFalse(identity.product.contains("sailfish"))
        assertFalse(identity.board.contains("sailfish"))
    }

    @Test
    fun matchingCatalogBrandFillsMissingDeviceFields() {
        val identity = RandomDeviceIdentity.from(
            template = ModuleConfig(brand = "samsung", model = "SM-S9480"),
            catalogBrand = samsungBrand,
            catalogModel = samsungBrand.models.single(),
            androidPreset = "16|36",
        )
        assertEquals("m3q", identity.device)
        assertEquals("m3q", identity.product)
        assertEquals("Galaxy S26 Ultra", identity.marketname)
    }

    @Test
    fun templateWithoutIdentityFallsBackToWholeCatalogEntry() {
        val identity = RandomDeviceIdentity.from(
            template = null,
            catalogBrand = googleBrand,
            catalogModel = googleBrand.models.single(),
            androidPreset = "16|36",
        )
        assertEquals("google", identity.brand)
        assertEquals("G-2PW4100", identity.model)
        assertEquals("Pixel", identity.marketname)
        assertEquals("sailfish", identity.device)
    }

    @Test
    fun fingerprintPartsRebuildMissingBuildFieldsConsistently() {
        // iQOO 15 Ultra 模板只带了指纹，其余 Build 字段从指纹解析，保持一致。
        val identity = RandomDeviceIdentity.from(
            template = ModuleConfig(
                brand = "vivo",
                model = "V2546A",
                fingerPrint = "vivo/PD2546/PD2546:16/BP2A.250605.031.A3/compiler260228234011:user/release-keys",
            ),
            catalogBrand = null,
            catalogModel = null,
            androidPreset = "10|29",
        )
        assertEquals("16", identity.androidVersion)
        assertEquals("36", identity.sdkInt)
        assertEquals("BP2A.250605.031.A3", identity.buildId)
        assertEquals("compiler260228234011", identity.incremental)
        assertEquals("PD2546", identity.product)
        assertEquals("PD2546", identity.device)
        assertEquals(templateFingerprint, identity.fingerPrint)
    }

    private val templateFingerprint =
        "vivo/PD2546/PD2546:16/BP2A.250605.031.A3/compiler260228234011:user/release-keys"

    @Test
    fun randomFieldsNeverContradictKeptFingerprint() {
        val identity = RandomDeviceIdentity.from(
            template = ModuleConfig(brand = "vivo", model = "V2546A", fingerPrint = templateFingerprint),
            catalogBrand = null,
            catalogModel = null,
            androidPreset = "10|29",
        )
        assertTrue(identity.fingerPrint.contains(":16/"))
        assertTrue(identity.fingerPrint.contains("BP2A.250605.031.A3"))
        assertTrue(identity.fingerPrint.contains("compiler260228234011"))
    }

    @Test
    fun inconsistentFingerprintIsRebuiltFromResolvedFields() {
        // 模板显式填写了与指纹矛盾的版本/构建号：指纹必须重建而不是保留。
        val identity = RandomDeviceIdentity.from(
            template = ModuleConfig(
                brand = "vivo",
                model = "V2546A",
                androidVersion = "10",
                buildId = "QP1A.190711.020",
                fingerPrint = templateFingerprint,
            ),
            catalogBrand = null,
            catalogModel = null,
            androidPreset = "16|36",
        )
        assertEquals("10", identity.androidVersion)
        assertEquals("QP1A.190711.020", identity.buildId)
        assertTrue(identity.fingerPrint.startsWith("vivo/"))
        assertTrue(identity.fingerPrint.contains(":10/QP1A.190711.020/"))
        assertNotEquals(templateFingerprint, identity.fingerPrint)
    }

    @Test
    fun malformedFingerprintFallsBackToReference() {
        val identity = RandomDeviceIdentity.from(
            template = ModuleConfig(brand = "samsung", model = "SM-S9480", fingerPrint = "not-a-fingerprint"),
            catalogBrand = null,
            catalogModel = null,
            androidPreset = "16|36",
        )
        assertTrue(identity.fingerPrint.startsWith("samsung/"))
        assertNull(ParsedFingerprint.parseFingerprint("not-a-fingerprint"))
        assertNull(ParsedFingerprint.parseFingerprint("a/b/c"))
    }

    @Test
    fun catalogSelectionFindsMatchingModelInsteadOfRandomBrand() {
        val unrelated = DeviceModel("SM-S900", "Other Galaxy", alias = "other", type = "mob")
        val brand = samsungBrand.copy(models = listOf(unrelated) + samsungBrand.models)
        val catalog = DeviceCatalog(1, brands = listOf(googleBrand, brand))
        repeat(20) {
            val picked = RandomDeviceIdentity.pickCatalogDevice(
                ModuleConfig(brand = "Samsung", model = "SM-S9480"), catalog,
            )!!
            assertEquals(brand, picked.first)
            assertEquals(samsungBrand.models.single(), picked.second)
        }
    }

    @Test
    fun sameBrandDifferentModelCannotFillMissingFields() {
        val other = DeviceModel("SM-S900", "Other Galaxy", alias = "other", type = "mob")
        val identity = RandomDeviceIdentity.from(
            ModuleConfig(brand = "samsung", model = "SM-S9480"),
            samsungBrand.copy(models = listOf(other)), other, "16|36",
        )
        assertEquals("SM-S9480", identity.model)
        assertEquals("SM-S9480", identity.device)
        assertFalse(identity.marketname.contains("Other"))
    }

    @Test
    fun fingerprintBrandAlsoConstrainsCatalogSelection() {
        val template = ModuleConfig(
            fingerPrint = "samsung/m3q/m3q:16/BP2A.250605.031/1234567:user/release-keys",
        )
        val catalog = DeviceCatalog(1, brands = listOf(googleBrand, samsungBrand))
        assertEquals(samsungBrand, RandomDeviceIdentity.pickCatalogDevice(template, catalog)!!.first)
        val identity = RandomDeviceIdentity.from(
            template, googleBrand, googleBrand.models.single(), "16|36",
        )
        assertEquals("samsung", identity.brand)
        assertEquals("samsung", identity.manufacturer)
        assertEquals("m3q", identity.device)
        assertEquals("", identity.model)
    }

    @Test
    fun missingCatalogMatchDoesNotSelectAnUnrelatedDevice() {
        val catalog = DeviceCatalog(1, brands = listOf(googleBrand))
        assertNull(RandomDeviceIdentity.pickCatalogDevice(
            ModuleConfig(brand = "samsung", model = "SM-S9480"), catalog,
        ))
    }

    @Test
    fun mismatchedBrandModelPairIsRejected() {
        val identity = RandomDeviceIdentity.from(
            null, samsungBrand, googleBrand.models.single(), "16|36",
        )
        assertEquals("", identity.model)
        assertEquals("", identity.marketname)
        assertFalse(identity.device.contains("sailfish"))
    }

    @Test
    fun explicitVersionWithoutFingerprintDeterminesSdk() {
        val identity = RandomDeviceIdentity.from(
            ModuleConfig(androidVersion = "10"), null, null, "16|36",
        )
        assertEquals("10", identity.androidVersion)
        assertEquals("29", identity.sdkInt)
    }

    @Test
    fun blankTemplateFieldsStayDeterministic() {
        val identity = RandomDeviceIdentity.from(
            template = null,
            catalogBrand = null,
            catalogModel = null,
            androidPreset = "16|36",
        )
        assertEquals("", identity.brand)
        assertEquals("16", identity.androidVersion)
        assertEquals("36", identity.sdkInt)
        assertEquals("default", identity.characteristics)
        assertTrue(identity.fingerPrint.contains(":16/"))
    }
}
