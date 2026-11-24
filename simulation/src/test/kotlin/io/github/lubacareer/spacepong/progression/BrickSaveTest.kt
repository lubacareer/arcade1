package io.github.lubacareer.spacepong.progression

import io.github.lubacareer.spacepong.bricks.BrickBuildings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
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
        assertEquals("3", json.getValue("version").toString())
        assertTrue(json.getValue("brickBestScores").jsonObject.isEmpty())
        assertEquals(legacy, Files.readString(directory.resolve("campaign.backup.json")))
        assertEquals(loaded.save, SaveRepository(directory).load().save)
    }

    @Test
    fun `v2 indices map to original building IDs and migrate only on commit`() {
        val primary = directory.resolve("campaign.json")
        val v2 = legacy.replace("\"version\":1", "\"version\":2").dropLast(1) + ""","brickBestScores":[1100,2200,3300,4400,5500,6600]}"""
        Files.writeString(primary, v2)
        val repository = SaveRepository(directory)
        val loaded = repository.load()
        assertFalse(loaded.readOnly)
        assertEquals(v2, Files.readString(primary))
        val edited = loaded.save.copy(settings = loaded.save.settings.copy(muted = false))
        repository.save(edited)
        val v3 = Json.parseToJsonElement(Files.readString(primary)).jsonObject
        val expected = mapOf("outpost" to "1100", "hangar" to "2200", "twin_towers" to "3300", "observatory" to "4400", "launch_gantry" to "5500", "orbital_citadel" to "6600")
        assertEquals(expected, v3.getValue("brickBestScores").jsonObject.mapValues { it.value.toString() })
        assertEquals(v2, Files.readString(directory.resolve("campaign.backup.json")))
        assertEquals(edited, SaveRepository(directory).load().save)
    }

    @Test
    fun `out of sequence and unknown v3 IDs survive unrelated changes`() {
        val document = legacy.replace("\"version\":1", "\"version\":3").dropLast(1) + ""","brickBestScores":{"orbital_citadel":2300,"retired_station":2500}}"""
        Files.writeString(directory.resolve("campaign.json"), document)
        val repository = SaveRepository(directory)
        val loaded = repository.load()
        assertFalse(loaded.readOnly)
        assertNull(loaded.notice)
        val reordered = listOf("hangar", "outpost", "orbital_citadel")
        assertTrue(loaded.save.isBuildingUnlocked("orbital_citadel", reordered))
        assertTrue(loaded.save.isBuildingUnlocked("hangar", reordered))
        assertFalse(loaded.save.isBuildingUnlocked("outpost", reordered))
        repository.save(loaded.save.copy(tutorialSeen = false))
        val updated = Json.parseToJsonElement(Files.readString(directory.resolve("campaign.json"))).jsonObject
        assertEquals(mapOf("orbital_citadel" to "2300", "retired_station" to "2500"), updated.getValue("brickBestScores").jsonObject.mapValues { it.value.toString() })
    }

    @Test
    fun `v3 building records require a nonempty ID and positive integer score`() {
        val base = legacy.replace("\"version\":1", "\"version\":3").dropLast(1)
        for (field in listOf("""{"outpost":0}""", """{"outpost":-1}""", """{"outpost":"200"}""", """{"":100}""")) {
            val document = "$base,\"brickBestScores\":$field}"
            assertThrows(IllegalArgumentException::class.java) { SaveCodec.decode(document) }
        }
    }

    @Test
    fun `large unknown building records cannot produce an unreadable save`() {
        val repository = SaveRepository(directory)
        val original = CampaignSave(tutorialSeen = true)
        repository.save(original)
        val oversized = original.copy(brickBestScores = mapOf("x".repeat(65_536) to 100))
        assertThrows(IOException::class.java) { repository.save(oversized) }
        assertEquals(original, SaveRepository(directory).load().save)
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
        assertTrue(original.isBuildingUnlocked("outpost"))
        assertFalse(original.isBuildingUnlocked("hangar"))
        assertThrows(IllegalArgumentException::class.java) { CampaignProgression.recordBrickVictory(original, "hangar", 300) }
        assertThrows(IllegalArgumentException::class.java) { CampaignProgression.recordBrickVictory(original, "outpost", 0) }
        val first = CampaignProgression.recordBrickVictory(original, "outpost", 500)
        assertEquals(original.credits, first.credits)
        assertTrue(first.isBuildingUnlocked("hangar"))
        assertFalse(first.isBuildingUnlocked("twin_towers"))
        assertSame(first, CampaignProgression.recordBrickVictory(first, "outpost", 400))
        assertSame(first, CampaignProgression.recordBrickVictory(first, "outpost", 500))
        val better = CampaignProgression.recordBrickVictory(first, "outpost", 800)
        var complete = better
        for (id in BrickBuildings.ids.drop(1)) complete = CampaignProgression.recordBrickVictory(complete, id, 900)
        val repository = SaveRepository(directory)
        repository.save(complete)
        val scores = SaveRepository(directory).load().save.brickBestScores
        assertEquals(listOf(800, 900, 900, 900, 900, 900), BrickBuildings.ids.map(scores::getValue))
    }

    @Test
    fun `classic and building resets affect only the selected campaign`() {
        val classic = SaveCodec.decode(legacy)
        val both = CampaignProgression.recordBrickVictory(classic, "outpost", 500)
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
        val scores = mutableMapOf("outpost" to 600)
        val original = CampaignSave(brickBestScores = scores)
        scores["outpost"] = 1
        assertEquals(600, original.brickBestScores["outpost"])
        val copied = original.copy(tutorialSeen = true)
        assertEquals(original.brickBestScores, copied.brickBestScores)
        assertNotEquals(copied, copied.copy(brickBestScores = emptyMap()))
        val first = SaveRepository(directory)
        val second = SaveRepository(directory)
        val firstSave = first.load().save
        val secondSave = second.load().save
        first.save(firstSave.copy(settings = firstSave.settings.copy(muted = true)))
        val stale = CampaignProgression.recordBrickVictory(secondSave, "outpost", 500)
        assertThrows(SaveConflictException::class.java) { second.save(stale) }
        val fresh = second.load().save
        val result = CampaignProgression.recordBrickVictory(fresh, "outpost", 500)
        second.save(result)
        val committed = first.load().save
        assertTrue(committed.settings.muted)
        assertEquals(500, committed.brickBestScores["outpost"])
    }
}
