package com.example.frogreader.data.parser

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.InputStream

class BookParsersCompatibilityTest {

    @Test
    fun `API 26 compatible prefix reader handles short and zero reads`() {
        val source = byteArrayOf(1, 2, 3, 4, 5)
        val input = object : InputStream() {
            var index = 0
            var returnedZero = false

            override fun read(): Int =
                if (index < source.size) source[index++].toInt() and 0xff else -1

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (!returnedZero) {
                    returnedZero = true
                    return 0
                }
                if (index >= source.size) return -1
                val count = minOf(2, length, source.size - index)
                source.copyInto(buffer, offset, index, index + count)
                index += count
                return count
            }
        }

        assertArrayEquals(source, BookParsers.readPrefix(input, 68))
    }

    @Test
    fun `prefix reader stops exactly at requested byte count`() {
        val input = byteArrayOf(9, 8, 7, 6).inputStream()

        assertArrayEquals(byteArrayOf(9, 8), BookParsers.readPrefix(input, 2))
        assertArrayEquals(byteArrayOf(7, 6), input.readBytes())
    }
}
