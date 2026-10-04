package com.github.kr328.clash.design.util

internal object ProxyOrdering {
    fun resolve(saved: List<String>, current: List<String>): List<String> =
        ProfileOrdering.normalizeOrderIds(saved, current)

    fun move(names: List<String>, name: String, direction: Int): List<String> {
        val from = names.indexOf(name)
        val to = from + direction
        if (from < 0 || to !in names.indices || direction !in listOf(-1, 1)) return names
        return names.toMutableList().apply { add(to, removeAt(from)) }
    }
}
