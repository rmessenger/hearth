package dev.hearth.audio

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.Date

// Real Java serialization, with synthetic content and the historical wire names.
internal fun positionBackupFixture(filename: String = "chapter10.mp3", seconds: Int = 65): ByteArray {
    val bytes = ByteArrayOutputStream().apply {
        ObjectOutputStream(this).use { it.writeObject(BackupFixture(mFileName = filename, mFilePosition = seconds)) }
    }.toByteArray()
    val replacements = mapOf(
        BackupFixture::class.java.name to "ak.alizandro.smartaudiobookplayer.BookDataBackup",
        BackupState::class.java.name to "ak.alizandro.smartaudiobookplayer.BookData\$BookState",
        EqualizerFixture::class.java.name to "ak.alizandro.smartaudiobookplayer.EqualizerLevels",
    )
    fun encoded(text: String): String = ByteArrayOutputStream().apply {
        DataOutputStream(this).use { it.writeUTF(text) }
    }.toByteArray().toString(Charsets.ISO_8859_1)
    var wire = bytes.toString(Charsets.ISO_8859_1)
    replacements.forEach { (old, new) ->
        wire = wire.replace(encoded(old), encoded(new))
            .replace(encoded("L${old.replace('.', '/')};"), encoded("L${new.replace('.', '/')};"))
    }
    return wire.toByteArray(Charsets.ISO_8859_1)
}

private data class BackupFixture(
    val mFilePosition: Int,
    val mFileName: String,
    val mBookCreationTime: Date = Date(0),
    val mBookState: BackupState = BackupState.Started,
    val mCoverName: String = "cover.jpg",
    val mEqualizerLevels: EqualizerFixture = EqualizerFixture(shortArrayOf(0, 200, -100)),
    val mPlaybackSpeed: Float = 1.5f,
    val mLastPlaybackTime: Long = 0,
) : Serializable { companion object { private const val serialVersionUID = 1L } }
private enum class BackupState { Started }
private data class EqualizerFixture(val levels: ShortArray) : Serializable
