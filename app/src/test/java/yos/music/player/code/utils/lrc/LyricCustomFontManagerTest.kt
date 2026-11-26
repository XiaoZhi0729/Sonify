package yos.music.player.code.utils.lrc

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LyricCustomFontManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `hasVariableWeightAxis detects wght axis in fvar table`() {
        val vf = writeTempFont("vf.ttf", buildMinimalSfnt(fvarAxisTags = listOf("wdth", "wght")))
        assertTrue(LyricCustomFontManager.hasVariableWeightAxis(vf))
    }

    @Test
    fun `hasVariableWeightAxis returns false when fvar lacks wght or is absent`() {
        val noFvar = writeTempFont("static.ttf", buildMinimalSfnt(fvarAxisTags = null))
        assertFalse(LyricCustomFontManager.hasVariableWeightAxis(noFvar))

        val wdthOnly = writeTempFont("wdth.ttf", buildMinimalSfnt(fvarAxisTags = listOf("wdth")))
        assertFalse(LyricCustomFontManager.hasVariableWeightAxis(wdthOnly))
    }

    @Test
    fun `hasVariableWeightAxis detects wght inside ttc collection`() {
        val sfnt = buildMinimalSfnt(fvarAxisTags = listOf("wght"), baseOffset = 16)
        val ttc = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { out ->
                out.writeBytes("ttcf")
                out.writeInt(0x00010000)
                out.writeInt(1) // numFonts
                out.writeInt(16) // offset of first font
                out.write(sfnt)
            }
        }.toByteArray()
        val file = writeTempFont("vf.ttc", ttc)
        assertTrue(LyricCustomFontManager.hasVariableWeightAxis(file))
    }

    private fun writeTempFont(name: String, bytes: ByteArray): File =
        tempFolder.newFile(name).apply { writeBytes(bytes) }

    private fun buildMinimalSfnt(fvarAxisTags: List<String>?, baseOffset: Int = 0): ByteArray {
        val tables = mutableListOf<Pair<String, ByteArray>>()
        tables += "head" to ByteArray(54)
        if (fvarAxisTags != null) {
            val fvarBytes = ByteArrayOutputStream().apply {
                DataOutputStream(this).use { out ->
                    out.writeShort(1) // majorVersion
                    out.writeShort(0) // minorVersion
                    out.writeShort(16) // axesArrayOffset
                    out.writeShort(2) // reserved
                    out.writeShort(fvarAxisTags.size) // axisCount
                    out.writeShort(20) // axisSize
                    out.writeShort(0) // instanceCount
                    out.writeShort(0) // instanceSize
                    for (tag in fvarAxisTags) {
                        out.writeBytes(tag)
                        out.write(ByteArray(16)) // min/default/max/flags/nameId
                    }
                }
            }.toByteArray()
            tables += "fvar" to fvarBytes
        }

        val headerSize = 12 + tables.size * 16
        var cursor = baseOffset + headerSize
        val offsets = tables.map { (_, data) ->
            val off = cursor
            cursor += data.size
            off
        }

        return ByteArrayOutputStream().apply {
            DataOutputStream(this).use { out ->
                out.writeInt(0x00010000) // sfntVersion TTF
                out.writeShort(tables.size)
                out.writeShort(16)
                out.writeShort(0)
                out.writeShort(0)
                tables.forEachIndexed { idx, (tag, data) ->
                    out.writeBytes(tag)
                    out.writeInt(0) // checksum
                    out.writeInt(offsets[idx])
                    out.writeInt(data.size)
                }
                tables.forEach { (_, data) -> out.write(data) }
            }
        }.toByteArray()
    }
}
