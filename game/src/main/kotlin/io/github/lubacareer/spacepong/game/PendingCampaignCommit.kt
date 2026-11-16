package io.github.lubacareer.spacepong.game

import io.github.lubacareer.spacepong.progression.CampaignSave
import io.github.lubacareer.spacepong.progression.SaveConflictException
import io.github.lubacareer.spacepong.progression.SaveDurabilityException
import java.io.IOException

/** A pending victory cannot be replaced by an unrelated settings/reset transaction. */
class PendingCampaignCommit(private val write: (CampaignSave) -> Unit) {
    var pending: CampaignSave? = null
        private set
    var error: String? = null
        private set
    var requiresReload: Boolean = false
        private set
    val canRetry: Boolean get() = pending != null && !requiresReload
    private var pendingPublished = false

    fun attempt(current: CampaignSave, next: CampaignSave): Boolean {
        if (requiresReload) return false
        if (pending == null && current == next) {
            error = null
            return true
        }
        return attempt(next)
    }

    /** Only the explicitly canceled transaction may be discarded. Conflicts require a fresh load. */
    fun cancelPending(expected: CampaignSave): Boolean {
        if (requiresReload || pending != expected) return false
        if (pendingPublished) {
            error = DURABILITY_NOTICE
            return false
        }
        pending = null
        error = null
        return true
    }

    fun attempt(next: CampaignSave): Boolean {
        if (requiresReload) return false
        if (pending != null && pending != next) {
            error = if (pendingPublished) DURABILITY_NOTICE else "Save your pending progress before making another change. Retry before exiting."
            return false
        }
        return try {
            write(next)
            pendingPublished = false
            pending = null
            error = null
            true
        } catch (failure: SaveConflictException) {
            pending = next
            requiresReload = true
            error = "Campaign changed in another game instance. Reopen the game to load the latest progress."
            false
        } catch (failure: SaveDurabilityException) {
            pending = next
            pendingPublished = true
            error = DURABILITY_NOTICE
            false
        } catch (failure: IOException) {
            pending = next
            error = if (pendingPublished) DURABILITY_NOTICE else "Could not save. Unsaved changes stay only in this session. RETRY SAVE before exiting."
            false
        }
    }
    private companion object {
        const val DURABILITY_NOTICE = "Campaign was written, but durability is unconfirmed. Cancellation is unavailable. RETRY SAVE before exiting."
    }
}
