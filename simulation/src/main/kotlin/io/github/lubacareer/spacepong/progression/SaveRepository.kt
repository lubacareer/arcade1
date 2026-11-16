package io.github.lubacareer.spacepong.progression

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

data class SaveLoadResult(
    val save: CampaignSave,
    val notice: String? = null,
    val readOnly: Boolean = false,
)

class UnsupportedSaveVersionException(val saveVersion: Int) : IOException("Save version " + saveVersion + " requires a newer game.")

class SaveConflictException : IOException("Campaign changed in another game instance. Reopen the game to load the latest progress.")

class SaveDurabilityException(cause: IOException) : IOException("Campaign was written, but storage durability could not be confirmed. Retry the save before closing the game.", cause)

/**
 * Keeps complete snapshots under a caller-provided app-private directory.
 * Both staged files and replacement targets are on the same filesystem.
 * A failed replacement leaves the previous primary/backup available.
 * Instances in this process coordinate by canonical directory and reject stale snapshots.
 * Call load before editing an existing campaign; conflicts require reloading its current state.
 * POSIX systems flush new directory entries before publication and each replacement afterward.
 * Windows NIO cannot open directory channels,
 * so desktop Windows retains atomic replacement and file flushing, not that extra guarantee.
 */
class SaveRepository internal constructor(
    directory: Path,
    private val syncDirectory: (Path) -> Unit,
) {
    constructor(directory: Path) : this(directory, ::forceDirectory)

    private val directory = directory.toFile().canonicalFile.toPath()
    private val accessLock = directoryLocks.computeIfAbsent(this.directory) { Any() }
    private var hasObservedPrimary = false
    private var observedPrimary: List<Byte>? = null

    private val primary = this.directory.resolve("campaign.json")
    private val backup = this.directory.resolve("campaign.backup.json")

    fun load(): SaveLoadResult = synchronized(accessLock) { loadUnlocked() }

    private fun loadUnlocked(): SaveLoadResult {
        val mainRead = read(primary)
        val backupRead = read(backup)
        remember(mainRead)
        val future = listOf(mainRead, backupRead).filterIsInstance<ReadResult.Future>().firstOrNull()
        if (future != null) {
            return SaveLoadResult(
                save = (mainRead as? ReadResult.Valid)?.save ?: (backupRead as? ReadResult.Valid)?.save ?: CampaignSave(),
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

    fun save(save: CampaignSave) = synchronized(accessLock) { saveUnlocked(save) }

    private fun saveUnlocked(save: CampaignSave) {
        ensureDurableDirectory(directory, syncDirectory)
        val current = read(primary)
        val previousBackup = read(backup)
        listOf(current, previousBackup).forEach {
            when (it) {
                is ReadResult.Future -> throw UnsupportedSaveVersionException(it.version)
                is ReadResult.Unavailable -> throw IOException("Cannot safely replace an unreadable campaign file.", it.error)
                else -> Unit
            }
        }
        if (hasObservedPrimary && observedPrimary != revision(current)) throw SaveConflictException()
        remember(current)
        val encoded = SaveCodec.encode(save).toByteArray(StandardCharsets.UTF_8)
        val pending = stage(encoded)
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
            atomicReplace(pending, primary) {
                // A failed directory flush can leave this rename visible. Keep retries safe.
                remember(ReadResult.Valid(save, encoded))
            }
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

    private fun atomicReplace(source: Path, destination: Path, published: () -> Unit = {}) {
        // Do not silently fall back to a non-atomic copy on unsupported filesystems.
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        published()
        try {
            syncDirectory(directory)
        } catch (failure: IOException) {
            if (destination == primary) throw SaveDurabilityException(failure)
            throw failure
        }
    }

    private fun remember(result: ReadResult) {
        observedPrimary = revision(result)
        hasObservedPrimary = true
    }

    private fun revision(result: ReadResult): List<Byte>? {
        val bytes = when (result) {
            is ReadResult.Valid -> result.bytes
            is ReadResult.Invalid -> result.bytes
            else -> return null // Future/unreadable files are rejected before writing.
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes).toList()
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
            ReadResult.Invalid(bytes)
        }
    }

    // An oversized file may belong to a newer schema; never overwrite it as damaged.
    private fun oversized(): ReadResult = ReadResult.Unavailable(IOException("Save exceeds this version's supported size."))

    private sealed interface ReadResult {
        data object Missing : ReadResult
        data class Invalid(val bytes: ByteArray) : ReadResult
        data class Valid(val save: CampaignSave, val bytes: ByteArray) : ReadResult
        data class Future(val version: Int) : ReadResult
        data class Unavailable(val error: IOException) : ReadResult
    }

    private companion object {
        const val MAX_SAVE_BYTES = 64 * 1024
        val directoryLocks = ConcurrentHashMap<Path, Any>()

        fun forceDirectory(path: Path) {
            // Windows providers deny directory channels. Do not swallow real POSIX I/O errors.
            if ("posix" !in path.fileSystem.supportedFileAttributeViews()) return
            FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
        }
    }
}

internal fun ensureDurableDirectory(directory: Path, syncDirectory: (Path) -> Unit) {
    try {
        if (!Files.readAttributes(directory, BasicFileAttributes::class.java).isDirectory) {
            throw NotDirectoryException(directory.toString())
        }
    } catch (_: NoSuchFileException) {
        val parent = directory.parent ?: throw IOException("Cannot create a missing filesystem root: $directory")
        ensureDurableDirectory(parent, syncDirectory)
        try {
            Files.createDirectory(directory)
        } catch (alreadyExists: FileAlreadyExistsException) {
            // Another repository may have created a shared ancestor while we prepared its parent.
            if (!Files.readAttributes(directory, BasicFileAttributes::class.java).isDirectory) throw alreadyExists
        }
    }
    // Even an existing directory may be left by a failed earlier mkdir/parent-sync attempt.
    // Confirm its entry before creating any descendants or publishing campaign files.
    directory.parent?.let(syncDirectory)
}
