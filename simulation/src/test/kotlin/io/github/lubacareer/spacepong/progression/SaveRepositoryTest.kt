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
}
