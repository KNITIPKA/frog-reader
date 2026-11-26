@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.example.frogreader.data

import com.example.frogreader.data.model.InternedFrbStyle
import java.io.OutputStream
import java.util.IdentityHashMap
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.modules.EmptySerializersModule

internal fun isInternedFrbStyle(descriptor: SerialDescriptor): Boolean {
    if (descriptor.isNullable) return false
    val annotations = descriptor.annotations
    if (annotations.isEmpty()) return false
    for (i in annotations.indices) {
        if (annotations[i] is InternedFrbStyle) return true
    }
    return false
}

/** Model payload inside a checked FRB block. Serializer field order is part of the model version. */
internal object FrbModelCodec {
    fun <T> encode(output: OutputStream, serializer: SerializationStrategy<T>, value: T) {
        val sink = FrbByteSink(limit = Long.MAX_VALUE)
        FrbModelEncoder(sink).encodeSerializableValue(serializer, value)
        sink.writeTo(output)
    }

    fun <T> encodeToBytes(
        serializer: SerializationStrategy<T>,
        value: T,
        limit: Long = Long.MAX_VALUE,
        initialCapacity: Int = 4096,
    ): ByteArray {
        val sink = FrbByteSink(limit = limit, initialCapacity = initialCapacity)
        FrbModelEncoder(sink).encodeSerializableValue(serializer, value)
        return sink.toByteArray()
    }

    fun <T> decode(bytes: ByteArray, serializer: DeserializationStrategy<T>): T {
        val decoder = FrbModelDecoder(bytes)
        return decoder.decodeSerializableValue(serializer).also { decoder.requireComplete() }
    }
}

private class FrbModelDecoder(private val bytes: ByteArray) : AbstractDecoder() {
    override val serializersModule = EmptySerializersModule()

    private var position = 0
    private var depth = 0
    private val slots = IntArray(FRB_MODEL_MAX_DEPTH)
    private val next = IntArray(FRB_MODEL_MAX_DEPTH)
    private val strings = ArrayList<String>()
    private val styles = IdentityHashMap<SerialDescriptor, ArrayList<Any?>>()

    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        val descriptor = deserializer.descriptor
        if (!isInternedFrbStyle(descriptor)) {
            return super.decodeSerializableValue(deserializer)
        }
        val table = styles.getOrPut(descriptor) { ArrayList() }
        val reference = readSize()
        if (reference == 0) {
            return super.decodeSerializableValue(deserializer).also {
                if (table.size < FRB_MODEL_DICTIONARY_LIMIT) table += it
            }
        }
        if (reference > table.size) invalid("Unknown style reference")
        // A table is scoped to the exact descriptor that produced every entry.
        @Suppress("UNCHECKED_CAST")
        return table[reference - 1] as T
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        if (depth == slots.size) invalid("Model nesting limit exceeded")
        val width = when (descriptor.kind) {
            StructureKind.LIST -> 1
            StructureKind.MAP -> 2
            else -> 0
        }
        slots[depth] = if (width == 0) RECORD else {
            val size = readSize()
            if (size > (bytes.size - position) / width) invalid("Collection exceeds block bounds")
            size * width
        }
        next[depth] = 0
        depth++
        return this
    }

    override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        val frame = depth - 1
        val size = slots[frame]
        if (size == RECORD) {
            val tag = readSize()
            if (tag != 0) {
                if (tag <= next[frame] || tag > descriptor.elementsCount) invalid("Invalid record field")
                next[frame] = tag
                return tag - 1
            }
        } else if (next[frame] < size) {
            return next[frame]++
        } else if (size == CLOSED) {
            invalid("Read past end of structure")
        }
        slots[frame] = CLOSED
        return CompositeDecoder.DECODE_DONE
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        if (depth == 0 || slots[depth - 1] != CLOSED) invalid("Unfinished structure")
        depth--
    }

    override fun decodeNotNullMark(): Boolean = decodeBoolean()

    override fun decodeBoolean(): Boolean = when (readByte()) {
        0 -> false
        1 -> true
        else -> invalid("Invalid boolean")
    }

    override fun decodeByte(): Byte = readByte().toByte()

    override fun decodeShort(): Short {
        val value = decodeInt()
        if (value !in Short.MIN_VALUE..Short.MAX_VALUE) invalid("Short out of range")
        return value.toShort()
    }

    override fun decodeInt(): Int {
        val value = readVarBits()
        if (value ushr Int.SIZE_BITS != 0L) invalid("Int out of range")
        return unzigzag(value).toInt()
    }

    override fun decodeLong(): Long = unzigzag(readVarBits())
    override fun decodeFloat(): Float = Float.fromBits(readFixed(Int.SIZE_BYTES).toInt())
    override fun decodeDouble(): Double = Double.fromBits(readFixed(Long.SIZE_BYTES))

    override fun decodeChar(): Char {
        val value = readSize()
        if (value > Char.MAX_VALUE.code) invalid("Char out of range")
        return value.toChar()
    }

    override fun decodeString(): String {
        val token = readSize()
        val size = token ushr 1
        if (token and 1 != 0) return strings.getOrNull(size) ?: invalid("Unknown string reference")
        if (size > bytes.size - position) invalid("String exceeds block bounds")
        val end = position + size
        val text = bytes.decodeToString(position, end)
        // The fast decoder uses U+FFFD for malformed UTF-8. Validate this
        // uncommon result strictly to distinguish it from literal U+FFFD.
        if ('\uFFFD' in text) bytes.decodeToString(position, end, throwOnInvalidSequence = true)
        position = end
        if (size in 1..FRB_MODEL_SHORT_STRING_BYTES && strings.size < FRB_MODEL_DICTIONARY_LIMIT) strings += text
        return text
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        val value = readSize()
        if (value >= enumDescriptor.elementsCount) invalid("Enum ordinal out of range")
        return value
    }

    fun requireComplete() {
        if (position != bytes.size || depth != 0) invalid("Trailing or unfinished model data")
    }

    private fun readSize(): Int {
        val p = position
        if (p < bytes.size) {
            val b = bytes[p].toInt()
            if (b >= 0) {
                position = p + 1
                return b
            }
        }
        val value = readVarBits()
        if (value !in 0L..Int.MAX_VALUE.toLong()) invalid("Unsigned integer out of range")
        return value.toInt()
    }

    private fun readByte(): Int {
        if (position == bytes.size) invalid("Truncated model block")
        return bytes[position++].toInt() and 0xff
    }

    private fun readFixed(size: Int): Long {
        if (size > bytes.size - position) invalid("Truncated fixed-width value")
        var result = 0L
        repeat(size) { index ->
            result = result or ((bytes[position++].toLong() and 0xff) shl (index * 8))
        }
        return result
    }

    private fun readVarBits(): Long {
        val p = position
        if (p < bytes.size) {
            val b = bytes[p].toInt()
            if (b >= 0) {
                position = p + 1
                return b.toLong()
            }
        }
        var result = 0L
        for (shift in 0..63 step 7) {
            val byte = readByte()
            if (shift == 63 && byte > 1 || shift != 0 && byte == 0) invalid("Invalid variable-width integer")
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte < 0x80) return result
        }
        invalid("Variable-width integer overflow")
    }

    private fun unzigzag(value: Long): Long = (value ushr 1) xor -(value and 1)

    private fun invalid(reason: String): Nothing = throw SerializationException("$reason at byte $position")

    private companion object {
        const val RECORD = -1
        const val CLOSED = -2
    }
}
