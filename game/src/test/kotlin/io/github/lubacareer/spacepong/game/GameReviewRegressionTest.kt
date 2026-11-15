package io.github.lubacareer.spacepong.game

import io.github.lubacareer.spacepong.input.TouchRouter
import io.github.lubacareer.spacepong.progression.CampaignSave
import io.github.lubacareer.spacepong.progression.SaveDurabilityException
import io.github.lubacareer.spacepong.progression.UpgradeRanks
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class GameReviewRegressionTest {
    @Test
    fun `successful retry refreshes the victory's earned credits and medals`() {
        val original = CampaignSave()
        val committed = CampaignSave(bestMedals = listOf(3, 0, 0, 0))
        val pendingResult = ResultSummary(true, false, "5 : 0", 0, 3, 0)
        val savedResult = pendingResult.withCommittedRewards(original, committed)
        assertEquals(3, savedResult.earned)
        assertEquals(3, savedResult.medals)
        assertEquals(savedResult, savedResult.withCommittedRewards(committed, committed))
    }

    @Test
    fun `respec and local match commits cannot become victory credit awards`() {
        val upgraded = CampaignSave(bestMedals = listOf(3, 0, 0, 0), upgrades = UpgradeRanks(height = 1))
        val respec = upgraded.copy(upgrades = UpgradeRanks())
        val result = ResultSummary(true, false, "5 : 0", 0, 3, 0)
        assertEquals(result, result.withCommittedRewards(upgraded, respec))
        val local = result.copy(local = true)
        assertEquals(local, local.withCommittedRewards(CampaignSave(), respec))
    }

    @Test
    fun `focus gain restores an explicitly resumed match but keeps interruption screens silent`() {
        assertTrue(audioMayActivateOnFocus(Page.MATCH))
        assertTrue(audioMayActivateOnFocus(Page.START))
        assertTrue(audioMayActivateOnFocus(Page.RESULTS))
        assertFalse(audioMayActivateOnFocus(Page.PAUSE))
        assertFalse(audioMayActivateOnFocus(Page.SETTINGS))
    }

    @Test fun `keep progress remains on confirmation when a published reset cannot be canceled`() {
        val flow = UiFlow().apply { show(Page.CONFIRM_NEW) }
        val reset = CampaignSave()
        val commits = PendingCampaignCommit { throw SaveDurabilityException(IOException("directory force")) }
        assertFalse(commits.attempt(CampaignSave(bestMedals = listOf(3, 0, 0, 0)), reset))
        assertFalse(flow.cancelReset(commits, reset))
        assertEquals(Page.CONFIRM_NEW, flow.page)
        assertEquals(reset, commits.pending)
    }

    @Test fun `keep progress returns home after canceling a reset that was never published`() {
        val flow = UiFlow().apply { show(Page.CONFIRM_NEW) }
        val reset = CampaignSave()
        val commits = PendingCampaignCommit { throw IOException("storage unavailable") }
        assertFalse(commits.attempt(CampaignSave(bestMedals = listOf(3, 0, 0, 0)), reset))
        assertTrue(flow.cancelReset(commits, reset))
        assertEquals(Page.START, flow.page)
        assertNull(commits.pending)
    }

    @Test fun `tutorial regrab follows the visible ghost without snapping to the paused paddle`() {
        val drill = TutorialDrill()
        drill.update(.1f, 500f, 0f, false)
        val initialGhost = drill.ghostY
        val router = TouchRouter(false)
        assertTrue(router.down(1, 100f, initialGhost, leftDragOrigin(drill, 360f), 360f))
        assertEquals(initialGhost, router.leftTargetY)
        router.move(1, 100f, initialGhost + 20f)
        assertEquals(initialGhost + 20f, router.leftTargetY)
        assertEquals(360f, leftDragOrigin(null, 360f))
    }
}
