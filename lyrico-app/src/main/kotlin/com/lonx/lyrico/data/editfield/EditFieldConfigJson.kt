package com.lonx.lyrico.data.editfield

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** v5 仅保留回放增益组件；兼容旧字段代码、显隐和组件总开关。 */
@Serializable
data class EditFieldConfigJson(
    val version: Int = 5,
    val fieldOrder: List<String> = emptyList(),
    val customTags: List<String> = emptyList(),
    val overrides: Map<String, Boolean> = emptyMap(),
    val componentOverrides: Map<String, Boolean> = emptyMap(),
) {
    fun toConfig(legacyOverrides: Map<String, Boolean> = emptyMap()): EditFieldConfig {
        val tags = customTags.mapNotNull(CustomTagKey::normalize).distinct()
        val known = EditFieldRegistry.knownCodes + tags.map(EditFieldRegistry::customTagCode)
        val sourceVisibility = if (version < 3) legacyOverrides + overrides else overrides
        val visibility = sourceVisibility
            .mapKeys { migrateCode(it.key) }.filterKeys { it in known }.toMutableMap()
        if (version < 3) {
            // 旧分组开关优先于子字段开关；展开后不再保留分组开关。
            EditFieldRegistry.fields.filter { sourceVisibility[it.groupCode] == false }
                .forEach { visibility[it.code] = false }
        }
        // 旧封面、歌词组件已拆为独立字段；关闭状态也需要保留。
        if (componentOverrides["component:Cover"] == false) {
            visibility["picture"] = false
            visibility["rating"] = false
        }
        if (componentOverrides["component:Lyrics"] == false) visibility["lyrics"] = false
        return EditFieldConfig(
            order = fieldOrder.map(::migrateCode).filter { it in known }.distinct(),
            customTags = tags,
            overrides = visibility,
            componentOverrides = componentOverrides.filterKeys { key ->
                EditFieldRegistry.fields.any { it.componentKey == key }
            },
        )
    }

    fun encode(): String = json.encodeToString(this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun from(config: EditFieldConfig) = EditFieldConfigJson(
            fieldOrder = config.orderedCodes(), customTags = config.customTags,
            overrides = config.overrides,
            componentOverrides = config.componentOverrides,
        )

        internal fun decode(config: String?, visibility: String?, tags: String?): EditFieldConfig {
            val stored = config?.let { runCatching { json.decodeFromString<EditFieldConfigJson>(it) }.getOrNull() }
            val legacyOverrides = visibility?.let {
                runCatching { json.decodeFromString<LegacyVisibility>(it).values }.getOrNull()
            }.orEmpty()
            val legacyTags = if (stored == null || stored.version < 3) tags?.let {
                runCatching { json.decodeFromString<LegacyTags>(it).visibleKeys }.getOrNull()
            }.orEmpty() else emptyList()
            return (stored ?: EditFieldConfigJson(version = 2)).let {
                it.copy(customTags = it.customTags + legacyTags).toConfig(legacyOverrides)
            }
        }

        private fun migrateCode(code: String): String {
            EditFieldRegistry.customTagKeyOf(code)?.let { key ->
                return CustomTagKey.normalize(key)?.let(EditFieldRegistry::customTagCode) ?: code
            }
            return EditFieldRegistry.fields.firstOrNull { "${it.groupCode}.${it.code}" == code }?.code ?: code
        }
    }
}

@Serializable
private data class LegacyVisibility(val values: Map<String, Boolean> = emptyMap())

@Serializable
private data class LegacyTags(val visibleKeys: List<String> = emptyList())
