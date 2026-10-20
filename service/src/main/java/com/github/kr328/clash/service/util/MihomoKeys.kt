package com.github.kr328.clash.service.util

/**
 * Key matching the way mihomo's structure decoder does it (common/structure, with
 * DefaultKeyReplacer): an exact key wins, otherwise any key that equals the field name
 * case-insensitively after `_` -> `-`. Security checks must look up keys this way, or a
 * subscription can hide a field from us by spelling it `Xdrive_Opts`.
 */
internal object MihomoKeys {
    fun matches(key: Any?, field: String): Boolean {
        val k = key as? String ?: return false
        return k == field || k.replace('_', '-').equals(field.replace('_', '-'), ignoreCase = true)
    }

    /** Every value in [map] whose key the engine could decode as [field]. */
    fun valuesOf(map: Map<*, *>, field: String): List<Any?> =
        map.entries.filter { matches(it.key, field) }.map { it.value }

    /** The value the engine reads for [field]: the exact key if present, else the first fuzzy match. */
    fun get(map: Map<*, *>, field: String): Any? =
        if (map.containsKey(field)) map[field] else valuesOf(map, field).firstOrNull()
}
