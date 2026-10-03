package com.houvven.guise.xposed.hook

import android.os.Build
import com.houvven.guise.xposed.LoadPackageHandler
import com.houvven.ktx_xposed.hook.beforeHookAllMethods
import com.houvven.ktx_xposed.hook.findClass
import com.houvven.ktx_xposed.hook.setStaticField
import com.houvven.ktx_xposed.logger.XposedLogger
import com.houvven.ktx_xposed.utils.runXposedCatching

class OsBuildHook : LoadPackageHandler {

    override fun onHook() {
        val fakedProps = mutableMapOf<String, String>()

        fun MutableMap<String, String>.putWithVariants(key: String, value: String) {
            put(key, value)
            expandPropVariants(key).forEach { put(it, value) }
        }

        config.run {
            // Triple: Build field name, configured value, matching ro.* property
            listOf(
                Triple("BRAND", brand, "ro.product.brand"),
                Triple("MANUFACTURER", manufacturer, "ro.product.manufacturer"),
                Triple("MODEL", model, "ro.product.model"),
                Triple("PRODUCT", product, "ro.product.name"),
                Triple("DEVICE", device, "ro.product.device"),
                Triple("BOARD", board, "ro.product.board"),
                Triple("HARDWARE", hardware, "ro.hardware"),
                Triple("ID", buildId, "ro.build.id"),
                Triple("FINGERPRINT", fingerPrint, "ro.build.fingerprint"),
                Triple("DISPLAY", displayId, "ro.build.display.id"),
            ).forEach { (field, value, propKey) ->
                if (value.isNotBlank()) {
                    Build::class.java.setStaticField(field, value)
                    fakedProps.putWithVariants(propKey, value)
                }
            }

            if (incremental.isNotBlank()) {
                Build.VERSION::class.java.setStaticField("INCREMENTAL", incremental)
                fakedProps.putWithVariants("ro.build.version.incremental", incremental)
            }
            if (securityPatch.isNotBlank()) {
                Build.VERSION::class.java.setStaticField("SECURITY_PATCH", securityPatch)
                fakedProps.putWithVariants("ro.build.version.security_patch", securityPatch)
            }
            if (marketname.isNotBlank()) {
                // No Build field; HyperOS/ColorOS surfaces and some apps read the
                // property directly. OPPO/OnePlus read the vendor oplus variant.
                fakedProps.putWithVariants("ro.product.marketname", marketname)
                fakedProps["ro.vendor.oplus.market.name"] = marketname
            }
            if (characteristics.isNotBlank()) {
                fakedProps["ro.build.characteristics"] = characteristics
            }

            if (androidVersion.isNotBlank()) {
                Build.VERSION::class.java.setStaticField("RELEASE", androidVersion)
                fakedProps.putWithVariants("ro.build.version.release", androidVersion)
            }
            if (sdkInt != -1) {
                if (sdkInt < Build.VERSION.SDK_INT) {
                    // Spoofing SDK_INT below the real level makes apps take legacy
                    // branches (e.g. skipping RECEIVER_EXPORTED flags) while the
                    // framework still enforces current rules -> startup crashes.
                    XposedLogger.i(
                        "OSBuild: skip SDK_INT spoof $sdkInt < real ${Build.VERSION.SDK_INT}"
                    )
                } else {
                    Build.VERSION::class.java.setStaticField<Any>("SDK_INT", sdkInt)
                    fakedProps.putWithVariants("ro.build.version.sdk", sdkInt.toString())
                }
            }
        }

        hookSystemProperties(fakedProps)
    }

    companion object {
        // Partition variant families, mirroring how bionic routes ro.* reads on
        // modern devices (notably OnePlus/OPPO). Only writing the base key leaves
        // apps that read a partition-specific variant with the real value.
        private val PRODUCT_PARTITIONS =
            listOf("odm", "vendor", "system", "system_ext", "product", "bootimage")
        private val BUILD_PARTITIONS = listOf("system", "vendor", "product")
        private val FINGERPRINT_PARTITIONS =
            listOf("system", "vendor", "product", "odm", "bootimage")
        private val SECURITY_PATCH_PARTITIONS = listOf(
            "system", "system_ext", "product", "vendor", "odm",
            "bootimage", "system_dlkm", "vendor_dlkm", "odm_dlkm",
        )

        private fun expandPropVariants(key: String): List<String> = when {
            key.startsWith("ro.product.") -> PRODUCT_PARTITIONS.map {
                "ro.product.$it.${key.removePrefix("ro.product.")}"
            }
            key == "ro.build.fingerprint" -> FINGERPRINT_PARTITIONS.map {
                "ro.$it.build.fingerprint"
            }
            key == "ro.build.id" -> BUILD_PARTITIONS.map { "ro.$it.build.id" }
            key == "ro.build.version.release" -> BUILD_PARTITIONS.map {
                "ro.$it.build.version.release"
            }
            key == "ro.build.version.sdk" -> BUILD_PARTITIONS.map {
                "ro.$it.build.version.sdk"
            }
            key == "ro.build.version.incremental" -> BUILD_PARTITIONS.map {
                "ro.$it.build.version.incremental"
            }
            key == "ro.build.version.security_patch" ->
                SECURITY_PATCH_PARTITIONS.map { "ro.$it.build.version.security_patch" } +
                    "ro.vendor.build.security_patch"
            else -> emptyList()
        }
    }

    /**
     * Many apps (and native code via __system_property_get) read ro.* properties
     * directly instead of the Build fields, and on newer ART the static final
     * Build fields may not be writable at all. Intercepting SystemProperties.get
     * covers both cases — same approach as the original Guise PropertiesHooker.
     */
    private fun hookSystemProperties(fakedProps: Map<String, String>) {
        if (fakedProps.isEmpty()) return
        runXposedCatching {
            val systemProperties = findClass("android.os.SystemProperties")

            // Covers get(String) and get(String, String)
            systemProperties.beforeHookAllMethods("get") { param ->
                val key = param.args.firstOrNull() as? String ?: return@beforeHookAllMethods
                fakedProps[key]?.let { param.result = it }
            }

            fakedProps["ro.build.version.sdk"]?.toIntOrNull()?.let { sdk ->
                systemProperties.beforeHookAllMethods("getInt") { param ->
                    if (param.args.firstOrNull() == "ro.build.version.sdk") {
                        param.result = sdk
                    }
                }
            }
        }
    }
}
