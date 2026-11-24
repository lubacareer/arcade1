package io.github.lubacareer.spacepong.progression

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

class ProcessSaveTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `writer in another JVM cannot enter between backup and primary replacement`() {
        val campaign = directory.resolve("campaign")
        val parent = SaveRepository(campaign)
        val initial = CampaignSave()
        parent.save(initial)
        val pending = parent.load().save.copy(settings = GameSettings(muted = true))
        val output = directory.resolve("writer.log")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx48m",
            "-cp",
            writerClasspath(),
            HoldingWriter::class.java.name,
            directory.toString(),
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            val ready = directory.resolve("holding")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (process.isAlive && !Files.exists(ready) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(Files.exists(ready)) { "Writer never reached backup publication: " + Files.readString(output) }
            val busy = assertThrows(IOException::class.java) { parent.save(pending) }
            assertFalse(busy is SaveConflictException || busy is SaveDurabilityException)
            assertEquals(initial, SaveRepository(campaign).load().save)
            Files.createFile(directory.resolve("release"))
            assertTrue(process.waitFor(15, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), Files.readString(output))
            assertThrows(SaveConflictException::class.java) { parent.save(pending) }
            val latest = parent.load().save
            assertTrue(latest.tutorialSeen)
            parent.save(latest.copy(settings = pending.settings))
            assertEquals(latest.copy(settings = pending.settings), SaveRepository(campaign).load().save)
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun `lock is permanent and a busy writer can retry after release`() {
        val repo = SaveRepository(directory)
        val initial = repo.load().save
        assertFalse(Files.exists(directory.resolve(".campaign.lock")))
        repo.save(initial)
        val lockFile = directory.resolve(".campaign.lock")
        val next = initial.copy(tutorialSeen = true)
        FileChannel.open(lockFile, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val busy = assertThrows(IOException::class.java) { repo.save(next) }
                assertFalse(busy is SaveConflictException)
                assertEquals(initial, SaveRepository(directory).load().save)
            }
        }
        repo.save(next)
        assertTrue(Files.isRegularFile(lockFile))
        assertEquals(next, SaveRepository(directory).load().save)
    }

    @Test
    fun `a fresh writer must observe an existing primary or recovery backup before replacement`() {
        val primary = directory.resolve("campaign.json")
        SaveRepository(directory).save(CampaignSave(tutorialSeen = true))
        val bytes = Files.readAllBytes(primary)
        assertThrows(SaveConflictException::class.java) { SaveRepository(directory).save(CampaignSave()) }
        assertArrayEquals(bytes, Files.readAllBytes(primary))
        Files.move(primary, directory.resolve("campaign.backup.json"))
        assertThrows(SaveConflictException::class.java) { SaveRepository(directory).save(CampaignSave()) }
        val replacement = SaveRepository(directory)
        val recovered = replacement.load().save
        replacement.save(recovered)
        assertTrue(SaveRepository(directory).load().save.tutorialSeen)
    }

    // Gradle's test worker uses its own class loader. Pass only this helper and its runtime dependencies.
    private fun writerClasspath(): String = listOf(
        HoldingWriter::class.java,
        SaveRepository::class.java,
        Unit::class.java,
        Json::class.java,
        KSerializer::class.java,
    ).map { File(it.protectionDomain.codeSource.location.toURI()) }.distinct().joinToString(File.pathSeparator)
}

object HoldingWriter {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args.single()).toRealPath()
        val campaign = root.resolve("campaign")
        var hasPaused = false
        val repository = SaveRepository(campaign) { synced ->
            if (synced == campaign && !hasPaused) {
                hasPaused = true
                check(Files.exists(campaign.resolve("campaign.backup.json")))
                Files.createFile(root.resolve("holding"))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                while (!Files.exists(root.resolve("release"))) {
                    check(System.nanoTime() < deadline) { "Parent did not release writer" }
                    Thread.sleep(10)
                }
            }
        }
        repository.save(repository.load().save.copy(tutorialSeen = true))
    }
}
