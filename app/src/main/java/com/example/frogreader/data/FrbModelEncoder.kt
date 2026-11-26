@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.example.frogreader.data

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.util.IdentityHashMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.AbstractEncoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.EmptySerializersModule

/** Unsynchronized growable byte sink with a hard byte limit. */
internal class FrbByteSink(
    private val limit: Long,
    initialCapacity: Int = 4096,
) {
    private var buffer = ByteArray(
        if (limit in 0L..initialCapacity.toLong()) {
            limit.toInt().coerceAtLeast(16)
        } else {
            initialCapacity.coerceAtLeast(64)
        },
    )
    private var size = 0

    fun writeByte(value: Int) {
        ensureCapacity(1)
        buffer[size++] = value.toByte()
    }

    fun writeFixed(bits: Long, byteCount: Int) {
        ensureCapacity(byteCount)
        val buf = buffer
        var pos = size
        var shift = 0
        repeat(byteCount) {
            buf[pos++] = (bits ushr shift).toByte()
            shift += 8
        }
        size = pos
    }

    fun writeUnsigned(value: Long) {
        if (value ushr 7 == 0L) {
            writeByte(value.toInt())
            return
        }
        val count = (70 - java.lang.Long.numberOfLeadingZeros(value)) / 7
        ensureCapacity(count)
        val buf = buffer
        var pos = size
        var bits = value
        while (bits ushr 7 != 0L) {
            buf[pos++] = ((bits.toInt() and 0x7f) or 0x80).toByte()
            bits = bits ushr 7
        }
        buf[pos++] = bits.toByte()
        size = pos
    }

    fun writeUtf8(value: String, utf8Len: Int) {
        ensureCapacity(utf8Len)
        val buf = buffer
        var pos = size
        val len = value.length
        if (utf8Len == len) {
            var i = 0
            while (i < len) {
                buf[pos++] = value[i++].code.toByte()
            }
            size = pos
            return
        }
        var i = 0
        while (i < len) {
            val c = value[i++].code
            if (c < 0x80) {
                buf[pos++] = c.toByte()
            } else if (c < 0x800) {
                buf[pos++] = (0xC0 or (c ushr 6)).toByte()
                buf[pos++] = (0x80 or (c and 0x3F)).toByte()
            } else if (c in 0xD800..0xDBFF) {
                val low = value[i++].code
                val cp = 0x10000 + ((c - 0xD800) shl 10) + (low - 0xDC00)
                buf[pos++] = (0xF0 or (cp ushr 18)).toByte()
                buf[pos++] = (0x80 or ((cp ushr 12) and 0x3F)).toByte()
                buf[pos++] = (0x80 or ((cp ushr 6) and 0x3F)).toByte()
                buf[pos++] = (0x80 or (cp and 0x3F)).toByte()
            } else {
                buf[pos++] = (0xE0 or (c ushr 12)).toByte()
                buf[pos++] = (0x80 or ((c ushr 6) and 0x3F)).toByte()
                buf[pos++] = (0x80 or (c and 0x3F)).toByte()
            }
        }
        size = pos
    }

    fun toByteArray(): ByteArray = if (size == buffer.size) buffer else buffer.copyOf(size)

    fun writeTo(output: OutputStream) {
        if (size > 0) output.write(buffer, 0, size)
    }

    private fun ensureCapacity(additional: Int) {
        if (additional < 0 || additional.toLong() > limit - size.toLong()) {
            throw IOException("Chapter/global block exceeds limit")
        }
        val required = size + additional
        if (required < 0) {
            throw IOException("Chapter/global block exceeds limit")
        }
        if (required > buffer.size) {
            grow(required)
        }
    }

    private fun grow(required: Int) {
        val maxAllowed = minOf(limit, MAX_ARRAY_SIZE.toLong()).toInt()
        if (required > maxAllowed) {
            throw IOException("Chapter/global block exceeds limit")
        }
        var newCapacity = buffer.size shl 1
        if (newCapacity < 0 || newCapacity > maxAllowed) {
            newCapacity = maxAllowed
        }
        if (newCapacity < required) {
            newCapacity = required
        }
        buffer = buffer.copyOf(newCapacity)
    }

    private companion object {
        const val MAX_ARRAY_SIZE = Int.MAX_VALUE - 8
    }
}

/** Writes the generated model schema into one bounded FRB payload. */
internal class FrbModelEncoder(private val sink: FrbByteSink) : AbstractEncoder() {
    override val serializersModule = EmptySerializersModule()
    private val taggedFields = BooleanArray(FRB_MODEL_MAX_DEPTH)
    private val strings = HashMap<String, Int>()
    private val styles = IdentityHashMap<SerialDescriptor, StyleDictionary>()
    private var depth = 0

    override fun shouldEncodeElementDefault(descriptor: SerialDescriptor, index: Int): Boolean = false

    override fun beginStructure(descriptor: SerialDescriptor): CompositeEncoder {
        if (descriptor.isCollection) throw SerializationException("FRB collection needs a size")
        enterStructure(tagged = true)
        return this
    }

    override fun beginCollection(descriptor: SerialDescriptor, collectionSize: Int): CompositeEncoder {
        if (!descriptor.isCollection || collectionSize < 0) throw SerializationException("Invalid FRB collection")
        enterStructure(tagged = false)
        sink.writeUnsigned(collectionSize.toLong())
        return this
    }

    override fun endStructure(descriptor: SerialDescriptor) {
        if (taggedFields[--depth]) sink.writeByte(0)
    }

    override fun encodeElement(descriptor: SerialDescriptor, index: Int): Boolean {
        if (taggedFields[depth - 1]) sink.writeUnsigned(index.toLong() + 1)
        return true
    }

    override fun <T> encodeSerializableValue(serializer: SerializationStrategy<T>, value: T) {
        val descriptor = serializer.descriptor
        if (!isInternedFrbStyle(descriptor)) {
            serializer.serialize(this, value)
            return
        }

        val style = requireNotNull(value)
        val dictionary = styles.getOrPut(descriptor) { StyleDictionary() }
        val existing = dictionary.indices[style]
        if (existing != null) {
            sink.writeUnsigned(existing.toLong() + 1)
            return
        }

        sink.writeUnsigned(0)
        serializer.serialize(this, value)
        if (dictionary.size < FRB_MODEL_DICTIONARY_LIMIT) {
            dictionary.indices.putIfAbsent(style, dictionary.size)
            dictionary.size++
        }
    }

    override fun encodeNull() = sink.writeByte(0)
    override fun encodeNotNullMark() = sink.writeByte(1)
    override fun encodeBoolean(value: Boolean) = sink.writeByte(if (value) 1 else 0)
    override fun encodeByte(value: Byte) = sink.writeByte(value.toInt() and 0xff)
    override fun encodeShort(value: Short) = encodeInt(value.toInt())
    override fun encodeInt(value: Int) = encodeLong(value.toLong())
    override fun encodeLong(value: Long) = sink.writeUnsigned((value shl 1) xor (value shr 63))
    override fun encodeChar(value: Char) = sink.writeUnsigned(value.code.toLong())
    override fun encodeEnum(enumDescriptor: SerialDescriptor, index: Int) = sink.writeUnsigned(index.toLong())
    override fun encodeFloat(value: Float) = sink.writeFixed(value.toRawBits().toLong(), Int.SIZE_BYTES)
    override fun encodeDouble(value: Double) = sink.writeFixed(value.toRawBits(), Long.SIZE_BYTES)

    override fun encodeString(value: String) {
        if (value.length in 1..FRB_MODEL_SHORT_STRING_BYTES) {
            val existing = strings[value]
            if (existing != null) {
                sink.writeUnsigned((existing.toLong() shl 1) or 1L)
                return
            }
        }

        val utf8Len = utf8EncodedLength(value)
        sink.writeUnsigned(utf8Len.toLong() shl 1)
        if (utf8Len > 0) {
            sink.writeUtf8(value, utf8Len)
        }
        if (utf8Len in 1..FRB_MODEL_SHORT_STRING_BYTES && strings.size < FRB_MODEL_DICTIONARY_LIMIT) {
            strings[value] = strings.size
        }
    }

    private fun enterStructure(tagged: Boolean) {
        if (depth == taggedFields.size) throw SerializationException("FRB model is nested too deeply")
        taggedFields[depth++] = tagged
    }

    private val SerialDescriptor.isCollection: Boolean
        get() = kind == StructureKind.LIST || kind == StructureKind.MAP

    private class StyleDictionary {
        val indices = HashMap<Any, Int>()
        var size = 0
    }
}

private fun utf8EncodedLength(value: String): Int {
    val len = value.length
    var i = 0
    while (i < len && value[i].code < 0x80) {
        i++
    }
    if (i == len) return len

    var utf8Len = len.toLong()
    while (i < len) {
        val c = value[i].code
        if (c < 0x80) {
            i++
        } else if (c < 0x800) {
            utf8Len += 1L
            i++
        } else if (c in 0xD800..0xDFFF) {
            if (c >= 0xDC00 || i + 1 >= len) {
                throw CharacterCodingException()
            }
            val low = value[i + 1].code
            if (low !in 0xDC00..0xDFFF) {
                throw CharacterCodingException()
            }
            utf8Len += 2L
            i += 2
        } else {
            utf8Len += 2L
            i++
        }
    }
    if (utf8Len > Int.MAX_VALUE) {
        throw IOException("Chapter/global block exceeds limit")
    }
    return utf8Len.toInt()
}

internal const val FRB_MODEL_MAX_DEPTH = 256
internal const val FRB_MODEL_DICTIONARY_LIMIT = 4096
internal const val FRB_MODEL_SHORT_STRING_BYTES = 128
