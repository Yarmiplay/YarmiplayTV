package com.yarmiplaytv.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class EmbeddedMediaTest {
    private val jpeg = ByteArray(40).also {
        it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte(); it[3] = 0xD9.toByte()
        it[4] = 0x11
    }

    @Test
    fun id3PictureTitleAndArtist() {
        val tag = id3(
            textFrame("TIT2", "Night Drive"),
            textFrame("TPE1", "The Rovers"),
            apic(jpeg),
        )
        val read = EmbeddedMedia.read(tag)
        assertEquals("Night Drive", read.title)
        assertEquals("The Rovers", read.artist)
        assertArrayEquals(jpeg, read.picture)
    }

    @Test
    fun flacPictureBlock() {
        val picture = pictureBlock(jpeg)
        val block = ByteArrayOutputStream()
        block.write("fLaC".toByteArray())
        val header = byteArrayOf(0x86.toByte(), (picture.size shr 16).toByte(), (picture.size shr 8).toByte(), picture.size.toByte())
        block.write(header)
        block.write(picture)
        val read = EmbeddedMedia.read(block.toByteArray())
        assertArrayEquals(jpeg, read.picture)
    }

    @Test
    fun mp4CoverAtom() {
        val payload = ByteArray(8) + jpeg // version/flags + reserved, then the image
        val dataAtom = u32(8 + payload.size) + "data".toByteArray() + payload
        val covr = u32(8 + dataAtom.size) + "covr".toByteArray() + dataAtom
        val read = EmbeddedMedia.read(covr)
        assertArrayEquals(jpeg, read.picture)
        assertNull(read.title)
    }

    @Test
    fun unknownBytesHaveNoPicture() {
        val read = EmbeddedMedia.read(ByteArray(64) { 1 })
        assertNull(read.picture)
        assertNull(read.title)
    }

    private fun id3(vararg frames: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        frames.forEach { body.write(it) }
        val size = body.size()
        val header = ByteArrayOutputStream()
        header.write("ID3".toByteArray())
        header.write(byteArrayOf(3, 0, 0))
        header.write(synchsafe(size))
        header.write(body.toByteArray())
        return header.toByteArray()
    }

    private fun textFrame(id: String, text: String): ByteArray {
        val data = byteArrayOf(0) + text.toByteArray(StandardCharsets.ISO_8859_1)
        return frame(id, data)
    }

    private fun apic(image: ByteArray): ByteArray {
        val data = ByteArrayOutputStream()
        data.write(0) // latin1
        data.write("image/jpeg".toByteArray())
        data.write(0) // mime terminator
        data.write(3) // front cover
        data.write(0) // empty description
        data.write(image)
        return frame("APIC", data.toByteArray())
    }

    private fun frame(id: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray())
        out.write(u32(data.size))
        out.write(byteArrayOf(0, 0))
        out.write(data)
        return out.toByteArray()
    }

    private fun pictureBlock(image: ByteArray): ByteArray {
        val mime = "image/jpeg".toByteArray()
        val out = ByteArrayOutputStream()
        out.write(u32(3)) // front cover
        out.write(u32(mime.size))
        out.write(mime)
        out.write(u32(0)) // description
        out.write(u32(1)); out.write(u32(1)); out.write(u32(24)); out.write(u32(0))
        out.write(u32(image.size))
        out.write(image)
        return out.toByteArray()
    }

    private fun u32(value: Int) = byteArrayOf(
        (value shr 24).toByte(), (value shr 16).toByte(), (value shr 8).toByte(), value.toByte(),
    )

    private fun synchsafe(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )
}
