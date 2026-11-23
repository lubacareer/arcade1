package io.github.lubacareer.spacepong.progression

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BrickSaveTest {
    @TempDir
    lateinit var directory: Path

    private val legacy = """{"version":1,"bestMedals":[3,2,0,0],"upgrades":{"height":1,"speed":1,"pulse":0},"tutorialSeen":true,"settings":{"muted":true,"largeText":true}}"""

    @Test
    fun `v1 loads without writing and the next atomic commit adds a fresh building campaign`() {
        val primary = directory.resolve("campaign.json")
        Files.writeString(primary, legacy)
        val repository = SaveRepository(directory)
        val loaded = repository.load()
        assertNull(loaded.notice)
        assertEquals(legacy, Files.readString(primary))
        assertEquals(listOf(3, 2, 0, 0), loaded.save.bestMedals)
        assertEquals(3, loaded.save.credits)
        assertEquals(UpgradeRanks(height = 1, speed = 1), loaded.save.upgrades)
        assertTrue(loaded.save.tutorialSeen)
        assertTrue(loaded.save.settings.muted && loaded.save.settings.largeText)
        repository.save(loaded.save)
        val json = Json.parseToJsonElement(Files.readString(primary)).jsonObject
        assertEquals("2", json.getValue("version").toString())
        assertEquals(List(6) { "0" }, json.getValue("brickBestScores").jsonArray.map { it.toString() })
        assertEquals(legacy, Files.readString(directory.resolve("campaign.backup.json")))
        assertEquals(loaded.save, SaveRepository(directory).load().save)
    }

    @Test
    fun `damaged v2 brick fields recover from a v1 backup`() {
        Files.writeString(directory.resolve("campaign.backup.json"), legacy)
        val base = legacy.replace("\"version\":1", "\"version\":2").dropLast(1)
        val invalidFields = listOf(
            "",
            ""","brickBestScores":[]""",
            ""","brickBestScores":[0,300,0,0,0,0]""",
            ""","brickBestScores":[-1,0,0,0,0,0]""",
            ""","brickBestScores":["300",0,0,0,0,0]""",
        )
        for (field in invalidFields) {
            Files.writeString(directory.resolve("campaign.json"), "$base$field}")
            val recovered = SaveRepository(directory).load()
            assertNotNull(recovered.notice, field)
            assertFalse(recovered.readOnly, field)
            assertEquals(3, recovered.save.credits, field)
        }
    }

    @Test
    fun `winning scores unlock in order and only personal bests are saved`() {
        val original = SaveCodec.decode(legacy)
        assertTrue(original.isBuildingUnlocked(0))
        assertFalse(original.isBuildingUnlocked(1))
        assertThrows(IllegalArgumentException::class.java) { CampaignProgression.recordBrickVictory(original, 1, 300) }
        assertThrows(IllegalArgumentException::class.java) { CampaignProgression.recordBrickVictory(original, 0, 0) }
        val first = CampaignProgression.recordBrickVictory(original, 0, 500)
        assertEquals(original.credits, first.credits)
        assertTrue(first.isBuildingUnlocked(1))
        assertFalse(first.isBuildingUnlocked(2))
        assertSame(first, CampaignProgression.recordBrickVictory(first, 0, 400))
        assertSame(first, CampaignProgression.recordBrickVictory(first, 0, 500))
        val better = CampaignProgression.recordBrickVictory(first, 0, 800)
        var complete = better
        for (building in 1..5) complete = CampaignProgression.recordBrickVictory(complete, building, 900)
        val repository = SaveRepository(directory)
        repository.save(complete)
        assertEquals(listOf(800, 900, 900, 900, 900, 900), SaveRepository(directory).load().save.brickBestScores)
    }

    @Test
    fun `classic and building resets affect only the selected campaign`() {
        val classic = SaveCodec.decode(legacy)
        val both = CampaignProgression.recordBrickVictory(classic, 0, 500)
        val resetClassic = CampaignProgression.resetClassic(both)
        assertEquals(0, resetClassic.credits)
        assertFalse(resetClassic.tutorialSeen)
        assertEquals(UpgradeRanks(), resetClassic.upgrades)
        assertEquals(both.brickBestScores, resetClassic.brickBestScores)
        assertEquals(both.settings, resetClassic.settings)
        assertEquals(classic, CampaignProgression.resetBricks(both))
    }

    @Test
    fun `building scores cannot mutate an existing snapshot or erase unrelated settings edits`() {
        val scores = mutableListOf(600, 0, 0, 0, 0, 0)
        val original = CampaignSave(brickBestScores = scores)
        scores[0] = 1
        assertEquals(600, original.brickBestScores[0])
        val copied = original.copy(tutorialSeen = true)
        assertEquals(original.brickBestScores, copied.brickBestScores)
        assertNotEquals(copied, copied.copy(brickBestScores = List(6) { 0 }))
        val first = SaveRepository(directory)
        val second = SaveRepository(directory)
        val firstSave = first.load().save
        val secondSave = second.load().save
        first.save(firstSave.copy(settings = firstSave.settings.copy(muted = true)))
        val stale = CampaignProgression.recordBrickVictory(secondSave, 0, 500)
        assertThrows(SaveConflictException::class.java) { second.save(stale) }
        val fresh = second.load().save
        val result = CampaignProgression.recordBrickVictory(fresh, 0, 500)
        second.save(result)
        val committed = first.load().save
        assertTrue(committed.settings.muted)
        assertEquals(500, committed.brickBestScores[0])
    }
}
