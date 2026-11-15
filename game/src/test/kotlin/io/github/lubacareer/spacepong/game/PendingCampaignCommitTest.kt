package io.github.lubacareer.spacepong.game

import io.github.lubacareer.spacepong.progression.CampaignProgression
import io.github.lubacareer.spacepong.progression.CampaignSave
import io.github.lubacareer.spacepong.progression.SaveConflictException
import io.github.lubacareer.spacepong.progression.SaveDurabilityException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class PendingCampaignCommitTest {
    @Test fun `a failed victory keeps its reward pending until retry succeeds`() {
        val original = CampaignSave()
        val victory = CampaignProgression.recordVictory(original, 0, 0)
        var fail = true
        var disk = original
        val commits = PendingCampaignCommit { if (fail) throw IOException("disk full") else disk = it }
        assertFalse(commits.attempt(victory))
        assertEquals(original, disk)
        assertEquals(victory, commits.pending)
        fail = false
        assertTrue(commits.attempt(victory))
        assertNull(commits.pending)
        assertEquals(3, disk.credits)
    }

    @Test fun `settings and new matches cannot replace an uncommitted victory`() {
        val victory = CampaignProgression.recordVictory(CampaignSave(), 0, 1)
        var writes = 0
        val commits = PendingCampaignCommit {
            writes++
            throw IOException("unavailable")
        }
        assertFalse(commits.attempt(victory))
        assertFalse(commits.attempt(CampaignSave()))
        assertEquals(victory, commits.pending)
        assertEquals(1, writes)
    }

    @Test fun `canceling a failed reset preserves progress and removes the destructive retry`() {
        val original = CampaignSave(bestMedals = listOf(3, 0, 0, 0))
        val reset = CampaignSave()
        var fail = true
        var disk = original
        val commits = PendingCampaignCommit { if (fail) throw IOException("full") else disk = it }
        assertFalse(commits.attempt(original, reset))
        assertTrue(commits.cancelPending(reset))
        assertNull(commits.pending)
        assertNull(commits.error)
        fail = false
        val settings = original.copy(settings = original.settings.copy(muted = true))
        assertTrue(commits.attempt(original, settings))
        assertEquals(original.bestMedals, disk.bestMedals)
    }

    @Test fun `canceling a reset cannot discard a different pending victory`() {
        val victory = CampaignProgression.recordVictory(CampaignSave(), 0, 0)
        val commits = PendingCampaignCommit { throw IOException("full") }
        assertFalse(commits.attempt(victory))
        assertFalse(commits.cancelPending(CampaignSave()))
        assertEquals(victory, commits.pending)
    }

    @Test fun `unchanged snapshots never touch failing storage or block subsequent play`() {
        var writes = 0
        val commits = PendingCampaignCommit {
            writes++
            throw IOException("full")
        }
        val current = CampaignSave(bestMedals = listOf(3, 0, 0, 0))
        assertTrue(commits.attempt(current, current.copy()))
        assertEquals(0, writes)
        assertNull(commits.pending)
        assertNull(commits.error)
    }

    @Test fun `a no op cannot clear a different pending victory`() {
        val original = CampaignSave()
        val victory = CampaignProgression.recordVictory(original, 0, 0)
        val commits = PendingCampaignCommit { throw IOException("full") }
        assertFalse(commits.attempt(original, victory))
        assertFalse(commits.attempt(original, original))
        assertEquals(victory, commits.pending)
    }

    @Test fun `conflicting snapshot requires reopen and cannot be retried or replaced`() {
        val original = CampaignSave()
        val victory = CampaignProgression.recordVictory(original, 0, 0)
        var writes = 0
        val commits = PendingCampaignCommit {
            writes++
            throw SaveConflictException()
        }
        assertFalse(commits.attempt(original, victory))
        assertTrue(commits.requiresReload)
        assertFalse(commits.canRetry)
        assertEquals(victory, commits.pending)
        assertTrue(commits.error!!.contains("Reopen"))
        assertFalse(commits.attempt(original, victory))
        assertFalse(commits.attempt(original, original))
        assertEquals(1, writes)
    }

    @Test fun `a published reset with uncertain durability cannot be canceled but can be retried`() {
        val current = CampaignSave(bestMedals = listOf(3, 0, 0, 0))
        val reset = CampaignSave()
        var fail = true
        val commits = PendingCampaignCommit { if (fail) throw SaveDurabilityException(IOException("directory force")) }
        assertFalse(commits.attempt(current, reset))
        assertTrue(commits.canRetry)
        assertFalse(commits.requiresReload)
        assertFalse(commits.cancelPending(reset))
        assertEquals(reset, commits.pending)
        assertTrue(commits.error!!.contains("durability"))
        fail = false
        assertTrue(commits.attempt(current, reset))
        assertNull(commits.pending)
        assertNull(commits.error)
    }

    @Test fun `a later retry failure cannot make an already published reset cancelable`() {
        val current = CampaignSave(bestMedals = listOf(3, 0, 0, 0))
        val reset = CampaignSave()
        var writes = 0
        val commits = PendingCampaignCommit {
            if (++writes == 1) throw SaveDurabilityException(IOException("directory force"))
            throw IOException("storage unavailable")
        }
        assertFalse(commits.attempt(current, reset))
        assertFalse(commits.attempt(current, reset))
        assertFalse(commits.cancelPending(reset))
        assertEquals(reset, commits.pending)
        assertTrue(commits.error!!.contains("durability"))
    }
}
