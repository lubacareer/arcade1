package io.github.lubacareer.spacepong.progression

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

data class SaveLoadResult(
    val save: CampaignSave,
    val notice: String? = null,
    val readOnly: Boolean = false,
)

class UnsupportedSaveVersionException(val saveVersion: Int) : IOException("Save version " + saveVersion + " requires a newer game.")

/**
 * Keeps complete snapshots under a caller-provided app-private directory.
 * Both staged files and replacement targets are on the same filesystem.
 * A failed replacement leaves the previous primary/backup available.
 */
class SaveRepository(private val directory: Path) {
    private val primary = directory.resolve("campaign.json")
    private val backup = directory.resolve("campaign.backup.json")

    @Synchronized
    fun load(): SaveLoadResult {
        val mainRead = read(primary)
        val backupRead = read(backup)
        val future = listOf(mainRead, backupRead).filterIsInstance<ReadResult.Future>().firstOrNull()
        if (future != null) {
            return SaveLoadResult(
                save = (mainRead as? ReadResult.Valid)?.save ?: CampaignSave(),
                notice = "This campaign was saved by a newer version. Existing files are preserved; saving is disabled.",
                readOnly = true,
            )
        }
        if (mainRead is ReadResult.Valid) {
            return SaveLoadResult(
                mainRead.save,
                notice = if (backupRead is ReadResult.Unavailable) "The backup could not be read. Saving is disabled." else null,
                readOnly = backupRead is ReadResult.Unavailable,
            )
        }
        if (backupRead is ReadResult.Valid) {
            return SaveLoadResult(
                backupRead.save,
                notice = "Recovered the last good campaign backup.",
                readOnly = mainRead is ReadResult.Unavailable,
            )
        }
        val unreadable = mainRead is ReadResult.Unavailable || backupRead is ReadResult.Unavailable
        val damaged = mainRead is ReadResult.Invalid || backupRead is ReadResult.Invalid
        return SaveLoadResult(
            CampaignSave(),
            notice = when {
                unreadable -> "Campaign files could not be read. Existing files are preserved; saving is disabled."
                damaged -> "Campaign files were damaged and no valid backup was available. A new campaign is ready."
                else -> null
            },
            readOnly = unreadable,
        )
    }

    @Synchronized
    fun save(save: CampaignSave) {
        Files.createDirectories(directory)
        val current = read(primary)
        val previousBackup = read(backup)
        listOf(current, previousBackup).forEach {
            when (it) {
                is ReadResult.Future -> throw UnsupportedSaveVersionException(it.version)
                is ReadResult.Unavailable -> throw IOException("Cannot safely replace an unreadable campaign file.", it.error)
                else -> Unit
            }
        }
        val pending = stage(SaveCodec.encode(save).toByteArray(StandardCharsets.UTF_8))
        try {
            // Never replace a healthy backup with a primary known to be corrupt.
            if (current is ReadResult.Valid) {
                val pendingBackup = stage(current.bytes)
                try {
                    atomicReplace(pendingBackup, backup)
                } finally {
                    Files.deleteIfExists(pendingBackup)
                }
            }
            atomicReplace(pending, primary)
        } finally {
            Files.deleteIfExists(pending)
        }
    }

    private fun stage(bytes: ByteArray): Path {
        val temporary = Files.createTempFile(directory, "campaign-", ".pending")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            return temporary
        } catch (error: IOException) {
            Files.deleteIfExists(temporary)
            throw error
        }
    }

    private fun atomicReplace(source: Path, destination: Path) {
        // Do not silently fall back to a non-atomic copy on unsupported filesystems.
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun read(path: Path): ReadResult {
        val bytes = try {
            if (Files.size(path) > MAX_SAVE_BYTES) return oversized()
            Files.readAllBytes(path)
        } catch (_: NoSuchFileException) {
            return ReadResult.Missing
        } catch (error: IOException) {
            return ReadResult.Unavailable(error)
        }
        if (bytes.size > MAX_SAVE_BYTES) return oversized()
        return try {
            ReadResult.Valid(SaveCodec.decode(String(bytes, StandardCharsets.UTF_8)), bytes)
        } catch (error: UnsupportedSaveVersionException) {
            ReadResult.Future(error.saveVersion)
        } catch (_: IllegalArgumentException) {
            ReadResult.Invalid
        }
    }

    // An oversized file may belong to a newer schema; never overwrite it as damaged.
    private fun oversized(): ReadResult = ReadResult.Unavailable(IOException("Save exceeds this version's supported size."))

    private sealed interface ReadResult {
        data object Missing : ReadResult
        data object Invalid : ReadResult
        data class Valid(val save: CampaignSave, val bytes: ByteArray) : ReadResult
        data class Future(val version: Int) : ReadResult
        data class Unavailable(val error: IOException) : ReadResult
    }

    private companion object {
        const val MAX_SAVE_BYTES = 64 * 1024
    }
}
