package io.github.lubacareer.spacepong.game

import io.github.lubacareer.spacepong.progression.CampaignProgression
import io.github.lubacareer.spacepong.progression.CampaignSave
import io.github.lubacareer.spacepong.progression.UpgradeRanks
import io.github.lubacareer.spacepong.progression.UpgradeTrack
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class UpgradePurchaseTest {
    @Test
    fun `a second captured button cannot spend the same last credit`() {
        val original = CampaignSave(bestMedals = listOf(1, 0, 0, 0))
        val first = requireNotNull(UpgradePurchase.candidate(original, UpgradeTrack.HEIGHT, false, false))
        assertEquals(0, first.credits)
        assertNull(UpgradePurchase.candidate(first, UpgradeTrack.SPEED, false, false))
        assertEquals(UpgradeRanks(height = 1), first.upgrades)
    }

    @Test
    fun `a stale command at maximum rank is ignored without weakening domain checks`() {
        val maxed = CampaignSave(bestMedals = listOf(3, 0, 0, 0), upgrades = UpgradeRanks(height = 2))
        assertNull(UpgradePurchase.candidate(maxed, UpgradeTrack.HEIGHT, false, false))
        assertThrows(IllegalArgumentException::class.java) { CampaignProgression.purchase(maxed, UpgradeTrack.HEIGHT) }
    }

    @Test
    fun `pending and read only saves refuse an otherwise affordable upgrade`() {
        val original = CampaignSave(bestMedals = listOf(3, 0, 0, 0))
        assertNull(UpgradePurchase.candidate(original, UpgradeTrack.HEIGHT, true, false))
        assertNull(UpgradePurchase.candidate(original, UpgradeTrack.HEIGHT, false, true))
        assertEquals(3, original.credits)
        assertEquals(UpgradeRanks(), original.upgrades)
    }

    @Test
    fun `an affordable current command still produces the intended purchase`() {
        val original = CampaignSave(bestMedals = listOf(3, 0, 0, 0), upgrades = UpgradeRanks(height = 1))
        val next = requireNotNull(UpgradePurchase.candidate(original, UpgradeTrack.HEIGHT, false, false))
        assertEquals(2, next.upgrades.height)
        assertEquals(0, next.credits)
        assertEquals(1, original.upgrades.height)
    }
}
