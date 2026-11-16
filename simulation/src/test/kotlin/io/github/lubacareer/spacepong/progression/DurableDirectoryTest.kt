package io.github.lubacareer.spacepong.progression

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class DurableDirectoryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `first save makes the campaign directory reachable before publishing any file`() {
        val campaign = directory.resolve("campaign")
        var parentSyncedBeforePublication = false
        val repo = SaveRepository(campaign) { path ->
            if (path == directory) {
                assertTrue(Files.isDirectory(campaign))
                assertEmpty(campaign)
                parentSyncedBeforePublication = true
            }
            if (path == campaign) assertTrue(parentSyncedBeforePublication)
        }

        repo.save(CampaignSave())
        assertTrue(parentSyncedBeforePublication)
        assertEquals(CampaignSave(), SaveRepository(campaign).load().save)
    }

    @Test
    fun `nested parents are synced before creating their descendants`() {
        val parent = directory.resolve("new-parent")
        val campaign = parent.resolve("campaign")
        var parentReachable = false
        var campaignReachable = false
        val repo = SaveRepository(campaign) { path ->
            if (path == directory) {
                assertTrue(Files.isDirectory(parent))
                assertFalse(Files.exists(campaign))
                parentReachable = true
            }
            if (path == parent) {
                assertTrue(parentReachable)
                assertTrue(Files.isDirectory(campaign))
                assertEmpty(campaign)
                campaignReachable = true
            }
            if (path == campaign) assertTrue(campaignReachable)
        }

        repo.save(CampaignSave())
        assertTrue(parentReachable)
        assertTrue(campaignReachable)
    }

    @Test
    fun `a failed parent sync is retried even though the directory now exists`() {
        verifyFailedPreparationCanRetry(freshRepository = false)
    }

    @Test
    fun `a fresh repository repairs a directory left by a failed parent sync`() {
        verifyFailedPreparationCanRetry(freshRepository = true)
    }

    @Test
    fun `preparation failure preserves existing primary and backup bytes`() {
        val initial = CampaignProgression.recordVictory(CampaignSave(), 0, 4)
        val current = CampaignProgression.recordVictory(initial, 0, 0)
        SaveRepository(directory).apply {
            save(initial)
            save(current)
        }
        val primary = Files.readAllBytes(directory.resolve("campaign.json"))
        val backup = Files.readAllBytes(directory.resolve("campaign.backup.json"))
        val repo = SaveRepository(directory) { path ->
            if (path == directory.parent) throw IOException("prepare failed")
        }

        val failure = assertThrows(IOException::class.java) { repo.save(current.copy(tutorialSeen = true)) }
        assertFalse(failure is SaveDurabilityException)
        assertEquals("prepare failed", failure.message)
        assertArrayEquals(primary, Files.readAllBytes(directory.resolve("campaign.json")))
        assertArrayEquals(backup, Files.readAllBytes(directory.resolve("campaign.backup.json")))
        Files.list(directory).use { assertEquals(2L, it.count()) }
    }

    @Test
    fun `a file in the directory chain is preserved and rejected`() {
        val parent = directory.resolve("not-a-directory")
        Files.writeString(parent, "keep this")
        val repo = SaveRepository(parent.resolve("campaign")) { fail("No directory should be synced") }

        assertThrows(IOException::class.java) { repo.save(CampaignSave()) }
        assertEquals("keep this", Files.readString(parent))
    }

    @Test
    fun `an existing filesystem root has no parent to sync`() {
        val root = directory.toAbsolutePath().root
        assertNull(root.parent)
        ensureDurableDirectory(root) { fail("A filesystem root has no containing directory") }
        assertTrue(Files.isDirectory(root))
    }

    private fun verifyFailedPreparationCanRetry(freshRepository: Boolean) {
        val parent = directory.resolve("new-parent")
        val campaign = parent.resolve("campaign")
        var failParentSync = true
        var parentRepaired = false
        val sync: (Path) -> Unit = { path ->
            if (path == directory) {
                assertTrue(Files.isDirectory(parent))
                assertFalse(Files.exists(campaign))
                if (failParentSync) throw IOException("new parent sync failed")
                parentRepaired = true
            }
            if (path == parent) {
                assertTrue(parentRepaired)
                assertEmpty(campaign)
            }
        }
        val repo = SaveRepository(campaign, sync)
        repo.load()
        val save = CampaignProgression.recordVictory(CampaignSave(), 0, 0)
        val failure = assertThrows(IOException::class.java) { repo.save(save) }
        assertFalse(failure is SaveDurabilityException)
        assertEquals("new parent sync failed", failure.message)
        assertTrue(Files.isDirectory(parent))
        assertEmpty(parent)
        assertFalse(Files.exists(campaign))

        failParentSync = false
        val retry = if (freshRepository) SaveRepository(campaign, sync) else repo
        retry.save(save)
        assertTrue(parentRepaired)
        assertEquals(save, SaveRepository(campaign).load().save)
    }

    private fun assertEmpty(path: Path) {
        Files.list(path).use { assertEquals(0L, it.count()) }
    }
}
