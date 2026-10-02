package com.houvven.guise.db.defaults

import android.content.Context
import com.houvven.guise.db.BundledTemplateState
import com.houvven.guise.db.Template
import com.houvven.guise.db.TemplateDBHelper
import com.houvven.guise.xposed.config.ModuleConfig
import java.security.MessageDigest

object BundledTemplateManager {
    private const val TEMPLATE_ID_PREFIX = "bundled:"

    fun synchronize(context: Context): List<Template> {
        val dao = TemplateDBHelper.templateDao
        TemplateDBHelper.runInTransaction {
            val templates = dao.getAll().associateBy(Template::id).toMutableMap()
            val states = dao.getBundledTemplateStates().associateBy(BundledTemplateState::seedId)
            val activeSeedIds = BundledTemplateCatalog.entries.mapTo(mutableSetOf()) { it.seedId }

            // Retire bundled templates that no longer ship (e.g. the library was
            // curated down). Only auto-delete when the user never touched them;
            // modified copies survive as user data.
            states.values
                .filter { it.seedId !in activeSeedIds && !it.deleted }
                .forEach { state ->
                    val current = templates[state.templateId]
                    if (current != null &&
                        state.managed &&
                        current.fingerprint() == state.installedFingerprint
                    ) {
                        dao.delete(current)
                        templates.remove(current.id)
                        dao.upsertBundledTemplateState(state.copy(deleted = true))
                    }
                }

            BundledTemplateCatalog.entries.forEach { spec ->
                val desired = spec.toTemplate(context)
                val desiredFingerprint = desired.fingerprint()
                val state = states[spec.seedId]

                when {
                    state?.deleted == true -> Unit
                    state == null -> {
                        val adopted = templates.values.firstOrNull { it.matches(desired) }
                        val installed = adopted ?: desired.also {
                            dao.insert(it)
                            templates[it.id] = it
                        }
                        dao.upsertBundledTemplateState(
                            BundledTemplateState(
                                seedId = spec.seedId,
                                templateId = installed.id,
                                installedVersion = spec.version,
                                installedFingerprint = installed.fingerprint(),
                                deleted = false,
                                managed = adopted == null,
                            )
                        )
                    }
                    templates[state.templateId] == null -> {
                        dao.upsertBundledTemplateState(state.copy(deleted = true))
                    }
                    state.managed &&
                        templates.getValue(state.templateId).fingerprint() == state.installedFingerprint &&
                        (state.installedVersion < spec.version || state.installedFingerprint != desiredFingerprint) -> {
                        val current = templates.getValue(state.templateId)
                        val updated = desired.copy(
                            id = current.id,
                            createTime = current.createTime,
                            updateTime = System.currentTimeMillis(),
                        )
                        dao.update(updated)
                        templates[updated.id] = updated
                        dao.upsertBundledTemplateState(
                            state.copy(
                                installedVersion = spec.version,
                                installedFingerprint = updated.fingerprint(),
                            )
                        )
                    }
                }
            }
        }
        return dao.getAll()
    }

    fun delete(template: Template) {
        val dao = TemplateDBHelper.templateDao
        TemplateDBHelper.runInTransaction {
            dao.getBundledTemplateStateByTemplateId(template.id)?.let { state ->
                dao.upsertBundledTemplateState(state.copy(deleted = true))
            }
            dao.delete(template)
        }
    }

    /** Batch variant of [delete] for removing a whole brand group at once. */
    fun deleteAll(templates: List<Template>) {
        val dao = TemplateDBHelper.templateDao
        TemplateDBHelper.runInTransaction {
            templates.forEach { template ->
                dao.getBundledTemplateStateByTemplateId(template.id)?.let { state ->
                    dao.upsertBundledTemplateState(state.copy(deleted = true))
                }
                dao.delete(template)
            }
        }
    }

    private fun BundledTemplateSpec.toTemplate(context: Context): Template = Template(
        id = "$TEMPLATE_ID_PREFIX$seedId",
        name = nameText ?: context.getString(nameRes),
        // descriptionRes defaults to 0 for text-only specs; getString(0) throws
        // Resources.NotFoundException, so only resolve when a real res id exists.
        description = descriptionText
            ?: descriptionRes.takeIf { it != 0 }?.let(context::getString),
        type = type,
        configuration = configuration().toJson(),
        packageName = packageName,
    )

    private fun Template.matches(other: Template): Boolean {
        if (type != other.type || packageName != other.packageName) return false
        val ownSignature = runCatching { ModuleConfig.fromJson(configuration).parameterSignature() }.getOrNull()
        val otherSignature = runCatching { ModuleConfig.fromJson(other.configuration).parameterSignature() }.getOrNull()
        return ownSignature != null && ownSignature == otherSignature
    }

    private fun Template.fingerprint(): String {
        val stable = copy(id = "", createTime = 0L, updateTime = 0L).serialization()
        return MessageDigest.getInstance("SHA-256")
            .digest(stable.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
