package io.github.lubacareer.spacepong.game

import io.github.lubacareer.spacepong.progression.CampaignProgression
import io.github.lubacareer.spacepong.progression.CampaignSave
import io.github.lubacareer.spacepong.progression.UpgradeTrack

/** Revalidates queued UI actions against the latest committed snapshot. */
object UpgradePurchase {
    fun candidate(save: CampaignSave, track: UpgradeTrack, readOnly: Boolean, hasPendingSave: Boolean): CampaignSave? {
        if (readOnly || hasPendingSave || save.upgrades.rank(track) >= 2) return null
        if (save.credits < save.upgrades.nextCost(track)) return null
        return CampaignProgression.purchase(save, track)
    }
}
