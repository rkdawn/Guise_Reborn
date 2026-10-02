package com.houvven.guise.db

import android.content.Context
import com.houvven.guise.ContextAmbient

/**
 * 用户自建分组的名册（持久化于 SharedPreferences）。
 * 模板通过 [Template.groupName] 归入某个自建分组；名册保证「空分组」也能存在，
 * 否则空分组会因没有任何模板引用而消失。
 */
object TemplateGroupStore {

    private const val PREFS_NAME = "template_groups"
    private const val KEY_NAMES = "custom_group_names"
    private const val KEY_SECTION_ORDER = "section_order"

    private val prefs
        get() = ContextAmbient.current.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun customGroups(): List<String> =
        (prefs.getStringSet(KEY_NAMES, emptySet()) ?: emptySet())
            .sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun exists(name: String): Boolean =
        customGroups().any { it.equals(name, ignoreCase = true) }

    fun add(name: String) = update { it + name }

    fun rename(oldName: String, newName: String) = update { names ->
        names.filterNot { it == oldName }.toSet() + newName
    }

    fun remove(name: String) = update { it - name }

    /** 用户手动拖拽排序后的分区顺序（section key 列表）；空 = 默认顺序。 */
    fun sectionOrder(): List<String> =
        prefs.getString(KEY_SECTION_ORDER, null)
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun saveSectionOrder(keys: List<String>) {
        prefs.edit().putString(KEY_SECTION_ORDER, keys.joinToString("\n")).apply()
    }

    private fun update(transform: (Set<String>) -> Set<String>) {
        val current = prefs.getStringSet(KEY_NAMES, emptySet()) ?: emptySet()
        prefs.edit().putStringSet(KEY_NAMES, transform(current.toSet())).apply()
    }
}
