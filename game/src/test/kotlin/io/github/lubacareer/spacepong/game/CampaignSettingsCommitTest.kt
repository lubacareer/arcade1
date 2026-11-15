package io.github.lubacareer.spacepong.game

import io.github.lubacareer.spacepong.progression.CampaignSave
import io.github.lubacareer.spacepong.progression.GameSettings
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class CampaignSettingsCommitTest {
    @Test
    fun `retrying a failed mute save applies audio exactly when persistence succeeds`() {
        var storageAvailable = false
        val pending = PendingCampaignCommit { if (!storageAvailable) throw IOException("Disk unavailable") }
        val appliedAudio = mutableListOf<GameSettings>()
        val appliedRefresh = mutableListOf<Boolean>()
        val transaction = CampaignSettingsCommit(pending::attempt, appliedAudio::add, appliedRefresh::add)
        val original = CampaignSave()
        val muted = original.copy(settings = original.settings.copy(muted = true))

        assertFalse(transaction.attempt(original, muted))
        assertTrue(appliedAudio.isEmpty())
        assertTrue(appliedRefresh.isEmpty())
        assertEquals(muted, pending.pending)

        storageAvailable = true
        assertTrue(transaction.attempt(original, requireNotNull(pending.pending)))
        assertEquals(listOf(muted.settings), appliedAudio)
        assertTrue(appliedRefresh.isEmpty())
        assertNull(pending.pending)
    }

    @Test
    fun `a retried refresh preference applies the display mode but later victories do not`() {
        var failuresRemaining = 1
        val pending = PendingCampaignCommit { if (failuresRemaining-- > 0) throw IOException("Disk unavailable") }
        val appliedAudio = mutableListOf<GameSettings>()
        val appliedRefresh = mutableListOf<Boolean>()
        val transaction = CampaignSettingsCommit(pending::attempt, appliedAudio::add, appliedRefresh::add)
        val original = CampaignSave()
        val highRefresh = original.copy(settings = original.settings.copy(highRefresh = true))

        assertFalse(transaction.attempt(original, highRefresh))
        assertTrue(transaction.attempt(original, requireNotNull(pending.pending)))
        assertEquals(listOf(true), appliedRefresh)
        assertEquals(listOf(highRefresh.settings), appliedAudio)

        val victory = highRefresh.copy(bestMedals = listOf(3, 0, 0, 0))
        assertTrue(transaction.attempt(highRefresh, victory))
        assertEquals(listOf(true), appliedRefresh)
        assertEquals(listOf(highRefresh.settings), appliedAudio)
    }

    @Test
    fun `a conflicting change while a save is pending applies no platform effects`() {
        val pending = PendingCampaignCommit { throw IOException("Disk unavailable") }
        val appliedAudio = mutableListOf<GameSettings>()
        val appliedRefresh = mutableListOf<Boolean>()
        val transaction = CampaignSettingsCommit(pending::attempt, appliedAudio::add, appliedRefresh::add)
        val original = CampaignSave()
        val muted = original.copy(settings = original.settings.copy(muted = true))
        assertFalse(transaction.attempt(original, muted))

        assertFalse(transaction.attempt(original, original.copy(settings = original.settings.copy(highRefresh = true))))
        assertEquals(muted, pending.pending)
        assertTrue(appliedAudio.isEmpty())
        assertTrue(appliedRefresh.isEmpty())
    }
}
