package io.github.lubacareer.spacepong.game

import io.github.lubacareer.spacepong.progression.CampaignSave
import io.github.lubacareer.spacepong.progression.GameSettings

/** The normal save and RETRY SAVE paths share the same post-commit platform effects. */
class CampaignSettingsCommit(
    private val commit: (CampaignSave) -> Boolean,
    private val applyAudio: (GameSettings) -> Unit,
    private val applyRefresh: (Boolean) -> Unit,
) {
    fun attempt(current: CampaignSave, next: CampaignSave): Boolean {
        if (!commit(next)) return false
        if (current.settings != next.settings) applyAudio(next.settings)
        if (current.settings.highRefresh != next.settings.highRefresh) applyRefresh(next.settings.highRefresh)
        return true
    }
}
