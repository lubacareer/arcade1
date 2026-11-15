package io.github.lubacareer.spacepong.game

import io.github.lubacareer.spacepong.progression.CampaignSave
import java.io.IOException

/** A pending victory cannot be replaced by an unrelated settings/reset transaction. */
class PendingCampaignCommit(private val write: (CampaignSave) -> Unit) {
    var pending: CampaignSave? = null
        private set
    var error: String? = null
        private set

    fun attempt(next: CampaignSave): Boolean {
        if (pending != null && pending != next) {
            error = "Save your pending progress before making another change."
            return false
        }
        return try {
            write(next)
            pending = null
            error = null
            true
        } catch (failure: IOException) {
            pending = next
            error = "Could not save progress. Your previous save is safe. Use RETRY SAVE."
            false
        }
    }
}
