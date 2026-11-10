package dev.hearth.audio

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream

/** Reads inert values only; never loads or deserializes classes from the backup. */
internal object LegacyPositionReader {
    const val FILE_NAME = "position.sabp.dat"
    private const val MAX_BYTES = 65_536
    private const val PREFIX = "ak.alizandro.smartaudiobookplayer."

    fun read(input: InputStream, tracks: List<Track>): BookProgress? = try {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        if (bytes.size > MAX_BYTES) null else {
            val fields = Reader(bytes).readBackup()
            val filename = (fields["mFileName"] as? Text)?.value
            val seconds = (fields["mFilePosition"] as? Numeric)?.value as? Int
            val track = tracks.singleOrNull { it.name == filename }
            if (track == null || seconds == null || seconds < 0 || track.durationMs <= 0 ||
                seconds * 1000L > track.durationMs + 2000) null
            else {
                val position = (seconds * 1000L).coerceAtMost(track.durationMs)
                val status = (fields["mBookState"] as? EnumValue)?.name
                BookProgress(trackUri = track.uri, positionMs = position,
                    status = status?.takeIf { it in setOf("New", "Started", "Finished") }
                        ?: if (position > 0 || tracks.indexOf(track) > 0) "Started" else "New")
            }
        }
    } catch (_: IOException) { null }

    private sealed interface Value
    private data class Text(val value: String) : Value
    private data class Numeric(val value: Number) : Value
    private data class EnumValue(val name: String) : Value
    private data class ObjectValue(val fields: Map<String, Value?>) : Value
    private data class Field(val type: Char, val name: String)
    private data class Description(val name: String, val uid: Long, val flags: Int,
        val fields: List<Field>, val parent: Description?) : Value
    private data object Ignored : Value

    private class Reader(bytes: ByteArray) {
        private val input = DataInputStream(ByteArrayInputStream(bytes))
        private val handles = mutableListOf<Value?>()
        private val classes = setOf("${PREFIX}BookDataBackup", "${PREFIX}BookData\$BookState",
            "${PREFIX}EqualizerLevels", "${PREFIX}RepeatSettings", "${PREFIX}SkipStartEndSettings",
            "java.util.Date", "java.lang.Enum")

        fun readBackup(): Map<String, Value?> {
            expect(input.readUnsignedShort() == 0xaced && input.readUnsignedShort() == 5)
            expect(input.readUnsignedByte() == 0x73)
            val description = description(0) ?: invalid()
            expect(description.name == "${PREFIX}BookDataBackup" && description.uid == 1L && description.flags == 2 && description.parent == null)
            val backup = objectValue(description, 0)
            expect(input.available() == 0)
            return backup.fields
        }

        private fun value(depth: Int): Value? {
            expect(depth <= 16)
            return when (input.readUnsignedByte()) {
                0x70 -> null
                0x71 -> reference()
                0x74 -> Text(input.readUTF()).also { add(it) }
                0x73 -> objectValue(description(depth + 1) ?: invalid(), depth + 1)
                0x7e -> {
                    val desc = description(depth + 1) ?: invalid()
                    expect(desc.flags == 0x12 && desc.fields.isEmpty())
                    val handle = add(null)
                    val name = value(depth + 1) as? Text ?: invalid()
                    EnumValue(name.value).also { handles[handle] = it }
                }
                0x75 -> {
                    val desc = description(depth + 1) ?: invalid()
                    expect(desc.name.length == 2 && desc.name[0] == '[' && desc.name[1] in "BCDFIJSZ")
                    add(Ignored)
                    val count = input.readInt()
                    expect(count in 0..4096)
                    repeat(count) { primitive(desc.name[1]) }
                    Ignored
                }
                else -> invalid()
            }
        }

        private fun description(depth: Int): Description? {
            expect(depth <= 16)
            return when (input.readUnsignedByte()) {
                0x70 -> null
                0x71 -> reference() as? Description ?: invalid()
                0x72 -> {
                    val name = input.readUTF()
                    expect(name in classes || (name.length == 2 && name[0] == '[' && name[1] in "BCDFIJSZ"))
                    val uid = input.readLong()
                    val handle = add(null)
                    val flags = input.readUnsignedByte()
                    expect(flags == 2 || flags == 0x12 || (flags == 3 && name == "java.util.Date"))
                    val count = input.readUnsignedShort()
                    expect(count <= 64)
                    val fields = List(count) {
                        val type = input.readUnsignedByte().toChar()
                        expect(type in "BCDFIJSZL[")
                        val field = Field(type, input.readUTF())
                        if (type == 'L' || type == '[') expect(value(depth + 1) is Text)
                        field
                    }
                    expect(fields.map { it.name }.distinct().size == fields.size)
                    expect(input.readUnsignedByte() == 0x78)
                    Description(name, uid, flags, fields, description(depth + 1)).also { handles[handle] = it }
                }
                else -> invalid()
            }
        }

        private fun objectValue(desc: Description, depth: Int): ObjectValue {
            expect(depth <= 16 && desc.flags in 2..3)
            val handle = add(null)
            val fields = mutableMapOf<String, Value?>()
            fun readClass(current: Description, level: Int) {
                expect(level <= 16 && current.flags in 2..3)
                current.parent?.let { readClass(it, level + 1) }
                current.fields.forEach { field ->
                    fields[field.name] = if (field.type == 'L' || field.type == '[') value(depth + 1) else primitive(field.type)
                }
                if (current.flags == 3) {
                    var token = input.readUnsignedByte()
                    while (token != 0x78) {
                        val size = when (token) {
                            0x77 -> input.readUnsignedByte()
                            0x7a -> input.readInt()
                            else -> invalid()
                        }
                        expect(size in 0..MAX_BYTES && size <= input.available())
                        input.skipBytes(size)
                        token = input.readUnsignedByte()
                    }
                }
            }
            readClass(desc, 0)
            return ObjectValue(fields).also { handles[handle] = it }
        }

        private fun primitive(type: Char): Value = when (type) {
            'B' -> Numeric(input.readByte())
            'C' -> Numeric(input.readUnsignedShort())
            'D' -> Numeric(input.readDouble())
            'F' -> Numeric(input.readFloat())
            'I' -> Numeric(input.readInt())
            'J' -> Numeric(input.readLong())
            'S' -> Numeric(input.readShort())
            'Z' -> { expect(input.readUnsignedByte() in 0..1); Ignored }
            else -> invalid()
        }

        private fun reference(): Value {
            val index = input.readInt() - 0x7e0000
            expect(index in handles.indices)
            return handles[index] ?: invalid()
        }
        private fun add(value: Value?): Int {
            expect(handles.size < 256)
            handles.add(value)
            return handles.lastIndex
        }
        private fun expect(valid: Boolean) { if (!valid) invalid() }
        private fun invalid(): Nothing = throw IOException("Unsupported position backup")
    }
}
