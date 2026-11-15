package io.github.lubacareer.spacepong.progression

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CampaignProgressionTest {
    @Test
    fun `only first encounter starts unlocked`() {
        val save = CampaignSave()
        assertTrue(save.isUnlocked(0))
        assertFalse(save.isUnlocked(1))
        assertEquals(0, save.credits)
        assertFalse(save.isComplete)
    }

    @Test
    fun `medal improvement pays once and unlocks next encounter`() {
        val cleared = CampaignProgression.recordVictory(CampaignSave(), 0, 4)
        assertEquals(listOf(1, 0, 0, 0), cleared.bestMedals)
        assertEquals(1, cleared.credits)
        assertTrue(cleared.isUnlocked(1))
        assertEquals(cleared, CampaignProgression.recordVictory(cleared, 0, 4))
        val improved = CampaignProgression.recordVictory(cleared, 0, 2)
        assertEquals(2, improved.credits)
        val perfect = CampaignProgression.recordVictory(improved, 0, 0)
        assertEquals(3, perfect.credits)
        assertEquals(perfect, CampaignProgression.recordVictory(perfect, 0, 1))
    }

    @Test
    fun `boss medals reflect three hull instead of regular duel threshold`() {
        val start = CampaignSave(bestMedals = listOf(1, 1, 1, 0))
        assertEquals(1, CampaignProgression.recordVictory(start, 3, 2).bestMedals[3])
        assertEquals(2, CampaignProgression.recordVictory(start, 3, 1).bestMedals[3])
        assertEquals(3, CampaignProgression.recordVictory(start, 3, 0).bestMedals[3])
    }

    @Test
    fun `full chapter supplies twelve credits and full upgrades cost nine`() {
        var save = CampaignSave(bestMedals = listOf(3, 3, 3, 3))
        assertTrue(save.isComplete)
        for (track in UpgradeTrack.entries) {
            save = CampaignProgression.purchase(save, track)
            save = CampaignProgression.purchase(save, track)
        }
        assertEquals(3, save.credits)
        assertEquals(UpgradeRanks(2, 2, 2), save.upgrades)
        assertEquals(1.16, save.upgrades.heightMultiplier, 0.000001)
        assertEquals(1.16, save.upgrades.speedMultiplier, 0.000001)
        assertEquals(5.0, save.upgrades.pulseCooldownSeconds, 0.000001)
        val reset = CampaignProgression.respec(save)
        assertEquals(12, reset.credits)
        assertEquals(UpgradeRanks(), reset.upgrades)
        assertEquals(reset, CampaignProgression.respec(reset))
    }

    @Test
    fun `invalid purchases and impossible progress are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CampaignProgression.purchase(CampaignSave(), UpgradeTrack.HEIGHT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CampaignProgression.purchase(
                CampaignSave(listOf(3, 3, 3, 3), UpgradeRanks(height = 2)),
                UpgradeTrack.HEIGHT,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CampaignProgression.recordVictory(CampaignSave(), 3, 0)
        }
        assertThrows(IllegalArgumentException::class.java) { CampaignSave(listOf(0, 1, 0, 0)) }
        assertThrows(IllegalArgumentException::class.java) { CampaignSave(upgrades = UpgradeRanks(height = 1)) }
        assertThrows(IllegalArgumentException::class.java) { UpgradeRanks(pulse = 3) }
        assertThrows(IllegalArgumentException::class.java) {
            CampaignProgression.recordVictory(CampaignSave(), 0, 5)
        }
    }

    @Test
    fun `save defensively copies medals`() {
        val mutable = mutableListOf(1, 0, 0, 0)
        val save = CampaignSave(mutable)
        mutable[0] = 0
        assertEquals(1, save.bestMedals[0])
        assertThrows(UnsupportedOperationException::class.java) {
            (save.bestMedals as MutableList<Int>)[0] = 0
        }
    }

    @Test
    fun `settings reject invalid volumes and retain explicit switches`() {
        assertThrows(IllegalArgumentException::class.java) { GameSettings(musicVolume = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { GameSettings(effectsVolume = 1.1f) }
        val save = CampaignSave(settings = GameSettings(muted = true, highContrast = true, largeText = true))
        assertTrue(save.settings.muted)
        assertEquals(save.settings, save.copy(tutorialSeen = true).settings)
    }
}
