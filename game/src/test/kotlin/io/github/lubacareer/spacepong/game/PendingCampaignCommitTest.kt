package io.github.lubacareer.spacepong.game

import io.github.lubacareer.spacepong.progression.CampaignProgression
import io.github.lubacareer.spacepong.progression.CampaignSave
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
}
