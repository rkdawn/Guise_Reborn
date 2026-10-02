package com.houvven.guise.db.defaults

import androidx.annotation.StringRes
import com.houvven.guise.R
import com.houvven.guise.db.Template
import com.houvven.guise.xposed.config.HooksValue
import com.houvven.guise.xposed.config.ModuleConfig

internal data class BundledTemplateSpec(
    val seedId: String,
    val version: Int,
    @StringRes val nameRes: Int = 0,
    @StringRes val descriptionRes: Int = 0,
    val nameText: String? = null,
    val descriptionText: String? = null,
    val type: Int = Template.Type.COMMON,
    val packageName: String? = null,
    val configuration: () -> ModuleConfig,
)

internal object BundledTemplateCatalog {
    val entries = listOf(
        BundledTemplateSpec(
            seedId = "blank-passport",
            version = 1,
            nameRes = R.string.bundled_template_blank_passport,
            descriptionRes = R.string.bundled_template_blank_passport_summary,
            configuration = {
                ModuleConfig(
                    passContacts = true,
                    passPhoto = true,
                    passVideo = true,
                    passAudio = true,
                    passApplications = true,
                )
            },
        ),
        BundledTemplateSpec(
            seedId = "force-screenshots",
            version = 1,
            nameRes = R.string.bundled_template_force_screenshots,
            descriptionRes = R.string.bundled_template_force_screenshots_summary,
            configuration = {
                ModuleConfig(screenshotsFlag = HooksValue.SCREENSHOTS_ENABLE)
            },
        ),
    ) + bundledDeviceTemplateEntries
}
