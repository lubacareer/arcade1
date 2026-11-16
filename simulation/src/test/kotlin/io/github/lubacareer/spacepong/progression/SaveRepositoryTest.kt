package io.github.lubacareer.spacepong.progression

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SaveRepositoryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `new installation has no warning and round trip preserves all state`() {
        val repo = SaveRepository(directory)
        assertNull(repo.load().notice)
        val save = CampaignSave(
            bestMedals = listOf(3, 2, 1, 0),
            upgrades = UpgradeRanks(1, 1, 1),
            tutorialSeen = true,
            settings = GameSettings(
                musicVolume = 0.15f, effectsVolume = 0.65f, muted = true,
                haptics = false, reducedMotion = true, screenShake = false,
                highContrast = true, largeText = true, lowEffects = true, highRefresh = true,
            ),
        )
        repo.save(save)
        assertEquals(save, SaveRepository(directory).load().save)
        assertFalse(repo.load().readOnly)
    }

    @Test
    fun `corrupt primary recovers last good backup without overwriting it with corruption`() {
        val repo = SaveRepository(directory)
        val first = CampaignProgression.recordVictory(CampaignSave(), 0, 4)
        val second = CampaignProgression.recordVictory(first, 0, 0)
        repo.save(first)
        repo.save(second)
        Files.writeString(directory.resolve("campaign.json"), "{broken")
        val recovered = repo.load()
        assertEquals(first, recovered.save)
        assertNotNull(recovered.notice)
        repo.save(second)
        Files.writeString(directory.resolve("campaign.json"), "{broken again")
        assertEquals(first, repo.load().save)
    }

    @Test
    fun `interrupted pending write is ignored and cannot duplicate medals`() {
        val repo = SaveRepository(directory)
        val saved = CampaignProgression.recordVictory(CampaignSave(), 0, 0)
        repo.save(saved)
        Files.writeString(directory.resolve("campaign.pending"), "{partial")
        val loaded = repo.load().save
        repo.save(CampaignProgression.recordVictory(loaded, 0, 0))
        assertEquals(3, repo.load().save.credits)
    }

    @Test
    fun `future version primary is preserved and blocks saving`() {
        val future = """{"version":99,"futureProgress":{"valuable":true}}"""
        val target = directory.resolve("campaign.json")
        Files.writeString(target, future)
        val repo = SaveRepository(directory)
        val loaded = repo.load()
        assertTrue(loaded.readOnly)
        assertNotNull(loaded.notice)
        assertThrows(UnsupportedSaveVersionException::class.java) { repo.save(CampaignSave()) }
        assertEquals(future, Files.readString(target))
    }

    @Test
    fun `future version backup is preserved even with a readable primary`() {
        val repo = SaveRepository(directory)
        val save = CampaignProgression.recordVictory(CampaignSave(), 0, 0)
        repo.save(save)
        val backup = directory.resolve("campaign.backup.json")
        val future = """{"version":2}"""
        Files.writeString(backup, future)
        assertEquals(save, repo.load().save)
        assertTrue(repo.load().readOnly)
        assertThrows(UnsupportedSaveVersionException::class.java) { repo.save(save) }
        assertEquals(future, Files.readString(backup))
    }

    @Test
    fun `malformed domain data is not mistaken for valid progress`() {
        val invalids = listOf(
            """{"version":1,"bestMedals":[0,0,0,3],"upgrades":{"height":0,"speed":0,"pulse":0}}""",
            """{"version":1,"bestMedals":[3,0,0,0],"upgrades":{"height":2,"speed":2,"pulse":2}}""",
            """{"version":1,"bestMedals":["3",0,0,0],"upgrades":{"height":0,"speed":0,"pulse":0}}""",
            """{"version":1,"bestMedals":[3.5,0,0,0],"upgrades":{"height":0,"speed":0,"pulse":0}}""",
        )
        for (invalid in invalids) {
            Files.writeString(directory.resolve("campaign.json"), invalid)
            val loaded = SaveRepository(directory).load()
            assertEquals(CampaignSave(), loaded.save)
            assertNotNull(loaded.notice)
        }
    }

    @Test
    fun `oversized future saves are preserved without parsing or overwriting`() {
        val future = "{\"version\":2,\"futureData\":\"" + "x".repeat(70_000) + "\"}"
        val primary = directory.resolve("campaign.json")
        Files.writeString(primary, future)
        val repo = SaveRepository(directory)
        assertTrue(repo.load().readOnly)
        assertThrows(java.io.IOException::class.java) { repo.save(CampaignSave()) }
        assertEquals(future, Files.readString(primary))
    }

    @Test
    fun `missing optional settings use defaults but incorrect field types fail`() {
        val valid = """{"version":1,"bestMedals":[1,0,0,0],"upgrades":{"height":0,"speed":0,"pulse":0}}"""
        Files.writeString(directory.resolve("campaign.json"), valid)
        assertEquals(GameSettings(), SaveRepository(directory).load().save.settings)
        Files.writeString(directory.resolve("campaign.json"), valid.dropLast(1) + ""","settings":{"muted":"true"}}""")
        assertNotNull(SaveRepository(directory).load().notice)
    }

    @Test
    fun `future version primary recovers supported backup in read only mode`() {
        val repo = SaveRepository(directory)
        val backupSave = CampaignProgression.recordVictory(CampaignSave(), 0, 1)
        repo.save(backupSave)
        repo.save(CampaignProgression.recordVictory(backupSave, 0, 0))
        val primary = directory.resolve("campaign.json")
        val backup = directory.resolve("campaign.backup.json")
        val backupBytes = Files.readAllBytes(backup)
        val future = """{"version":99,"valuableFutureProgress":true}"""
        Files.writeString(primary, future)

        val loaded = repo.load()
        assertEquals(backupSave, loaded.save)
        assertTrue(loaded.readOnly)
        assertNotNull(loaded.notice)
        assertThrows(UnsupportedSaveVersionException::class.java) { repo.save(backupSave) }
        assertEquals(future, Files.readString(primary))
        assertArrayEquals(backupBytes, Files.readAllBytes(backup))
    }

    @Test
    fun `every published snapshot is synced before save reports success`() {
        val observed = mutableListOf<Pair<CampaignSave, CampaignSave?>>()
        val repo = SaveRepository(directory) { path ->
            if (path == directory) {
                val primary = SaveCodec.decode(Files.readString(path.resolve("campaign.json")))
                val backup = path.resolve("campaign.backup.json")
                observed += primary to if (Files.exists(backup)) SaveCodec.decode(Files.readString(backup)) else null
            }
        }
        val first = CampaignProgression.recordVictory(CampaignSave(), 0, 4)
        val second = CampaignProgression.recordVictory(first, 0, 0)
        repo.save(first)
        repo.save(second)
        assertEquals(listOf(first to null, first to first, second to first), observed)
    }

    @Test
    fun `directory sync failure is reported without destroying the prior snapshot`() {
        val first = CampaignProgression.recordVictory(CampaignSave(), 0, 4)
        val second = CampaignProgression.recordVictory(first, 0, 0)
        SaveRepository(directory).save(first)
        val repo = SaveRepository(directory) { path ->
            if (path == directory) throw java.io.IOException("disk flush failed")
        }
        val failure = assertThrows(java.io.IOException::class.java) { repo.save(second) }
        assertFalse(failure is SaveDurabilityException)
        assertEquals(first, SaveRepository(directory).load().save)
    }

    @Test
    fun `a stale repository cannot overwrite progress committed by another instance`() {
        val first = SaveRepository(directory)
        val second = SaveRepository(directory.resolve(".").resolve("nested").resolve(".."))
        val firstLoaded = first.load().save
        val stale = second.load().save
        val victory = CampaignProgression.recordVictory(firstLoaded, 0, 0)
        first.save(victory)

        assertThrows(SaveConflictException::class.java) {
            second.save(stale.copy(settings = stale.settings.copy(muted = true)))
        }
        assertEquals(victory, SaveRepository(directory).load().save)
        val reloaded = second.load().save
        second.save(reloaded.copy(settings = reloaded.settings.copy(muted = true)))
        assertEquals(3, SaveRepository(directory).load().save.credits)
        assertTrue(SaveRepository(directory).load().save.settings.muted)
    }

    @Test
    fun `a failed final directory sync can retry the same campaign safely`() {
        val first = CampaignProgression.recordVictory(CampaignSave(), 0, 4)
        val second = CampaignProgression.recordVictory(first, 0, 0)
        SaveRepository(directory).save(first)
        var failAfterPublication = true
        val repo = SaveRepository(directory) { path ->
            if (path == directory && failAfterPublication && SaveCodec.decode(Files.readString(directory.resolve("campaign.json"))) == second) {
                failAfterPublication = false
                throw java.io.IOException("primary directory flush failed")
            }
        }
        repo.load()
        val failure = assertThrows(SaveDurabilityException::class.java) { repo.save(second) }
        assertEquals("primary directory flush failed", failure.cause?.message)
        assertEquals(second, SaveRepository(directory).load().save)
        repo.save(second)
        assertEquals(second, SaveRepository(directory).load().save)
        assertEquals(3, SaveRepository(directory).load().save.credits)
    }
}
