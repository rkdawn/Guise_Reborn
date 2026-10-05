package com.houvven.guise.db

import com.houvven.guise.xposed.config.ModuleConfig
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TemplatePersistenceTest {
    private fun template(id: String = "bundled:sample") = Template(
        id = id,
        name = "设备档案",
        type = Template.Type.COMMON,
        configuration = ModuleConfig(brand = "google", model = "Pixel 9").toJson(),
        createTime = 1L,
        updateTime = 1L,
    )

    private fun prepareForExport(templates: List<Template>, states: List<BundledTemplateState>) =
        TemplateTransfer.prepareForExport(templates, states, setOf("sample"))

    private fun installedState(template: Template, managed: Boolean = true) = BundledTemplateState(
        seedId = "sample",
        templateId = template.id,
        installedVersion = 1,
        installedFingerprint = MessageDigest.getInstance("SHA-256")
            .digest(template.copy(id = "", createTime = 0L, updateTime = 0L).serialization().toByteArray())
            .joinToString("") { "%02x".format(it) },
        deleted = false,
        managed = managed,
    )

    @Test
    fun savingRoutedEditsPreservesLatestDatabaseGroup() {
        val original = template().copy(groupName = "旧分组")
        val edited = Template.deserialization(original.serialization()).copy(name = "新名称")
        assertNull(edited.groupName)
        val current = original.copy(groupName = "最新分组")
        val saved = current.withEdits(edited)
        assertEquals("最新分组", saved.groupName)
        assertEquals("新名称", saved.name)
        assertEquals(current.id, saved.id)
        assertEquals(current.createTime, saved.createTime)
    }

    @Test
    fun exportingKeepsUserChangesAndAvoidsBundledIdCollision() {
        val original = template()
        val changed = original.copy(name = "我的修改")
        val custom = template("custom-template")
        val exportable = prepareForExport(
            listOf(changed, custom), listOf(installedState(original)),
        )
        assertEquals(2, exportable.size)
        val restored = TemplateTransfer.decode(TemplateTransfer.encode(exportable))
        val exportedChange = restored.single { it.name == changed.name }
        assertEquals(changed.configuration, exportedChange.configuration)
        assertFalse(exportedChange.id.startsWith("bundled:"))
        assertNotEquals(original.id, exportedChange.id)
        assertEquals(custom, restored.single { it.id == custom.id })
    }

    @Test
    fun unchangedManagedTemplateCanBeOmitted() {
        val original = template()
        assertEquals(emptyList<Template>(), prepareForExport(
            listOf(original), listOf(installedState(original)),
        ))
    }

    @Test
    fun missingStateAndAdoptedUserTemplatesAreNeverOmitted() {
        val original = template()
        assertEquals(1, prepareForExport(listOf(original), emptyList()).size)
        assertEquals(1, prepareForExport(
            listOf(original), listOf(installedState(original, managed = false)),
        ).size)
    }

    @Test
    fun retiredTemplatesAreKeptEvenWhenTheirContentIsUnchanged() {
        val original = template()
        assertEquals(1, TemplateTransfer.prepareForExport(
            listOf(original), listOf(installedState(original)), emptySet(),
        ).size)
    }

    @Test
    fun successiveEditsExportAsDistinctRestorableCopies() {
        val original = template()
        val first = prepareForExport(listOf(original.copy(name = "第一次修改")), emptyList()).single()
        val second = prepareForExport(listOf(original.copy(name = "第二次修改")), emptyList()).single()
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun exportIdsAreStableWithoutMutatingDatabaseObjects() {
        val original = template()
        val first = prepareForExport(listOf(original), emptyList())
        val second = prepareForExport(listOf(original), emptyList())
        assertEquals(first, second)
        assertEquals("bundled:sample", original.id)
    }
}
