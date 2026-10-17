package com.example.frogreader.ui.reader

import java.util.concurrent.ConcurrentHashMap

/** A read-only immutable-source map whose transformed values are shared on first use. */
internal class LazyMappedValuesMap<K : Any, V : Any, R : Any>(
    private val source: Map<K, V>,
    private val transform: (K, V) -> R,
) : AbstractMap<K, R>() {
    private val cached = ConcurrentHashMap<K, Lazy<R>>()

    override val size: Int get() = source.size
    override val keys: Set<K> get() = source.keys
    override fun isEmpty(): Boolean = source.isEmpty()
    override fun containsKey(key: K): Boolean = source.containsKey(key)

    override fun get(key: K): R? {
        val original = source[key] ?: return null
        // ConcurrentMap.getOrPut may create two Lazy holders, but returns the
        // winning holder to both callers before either executes the transform.
        return cached.getOrPut(key) {
            lazy(LazyThreadSafetyMode.SYNCHRONIZED) { transform(key, original) }
        }.value
    }

    override val entries: Set<Map.Entry<K, R>> = object : AbstractSet<Map.Entry<K, R>>() {
        override val size: Int get() = source.size

        override fun contains(element: Map.Entry<K, R>): Boolean =
            this@LazyMappedValuesMap.containsKey(element.key) &&
                this@LazyMappedValuesMap[element.key] == element.value

        override fun iterator(): Iterator<Map.Entry<K, R>> {
            val originals = source.entries.iterator()
            return object : Iterator<Map.Entry<K, R>> {
                override fun hasNext(): Boolean = originals.hasNext()

                override fun next(): Map.Entry<K, R> {
                    val key = originals.next().key
                    return DeferredEntry(key) { this@LazyMappedValuesMap.getValue(key) }
                }
            }
        }
    }
}

/** Merely traversing entry keys must not prepare values of distant notes. */
private class DeferredEntry<K : Any, V : Any>(
    override val key: K,
    private val valueProvider: () -> V,
) : Map.Entry<K, V> {
    override val value: V get() = valueProvider()
    override fun equals(other: Any?): Boolean =
        other is Map.Entry<*, *> && key == other.key && value == other.value
    override fun hashCode(): Int = key.hashCode() xor value.hashCode()
    override fun toString(): String = "$key=$value"
}
