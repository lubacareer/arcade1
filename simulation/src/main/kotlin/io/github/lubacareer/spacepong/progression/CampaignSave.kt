package io.github.lubacareer.spacepong.progression

import io.github.lubacareer.spacepong.bricks.BrickBuildings
import java.util.Collections

enum class UpgradeTrack { HEIGHT, SPEED, PULSE }

/** Ranks contain only earned campaign upgrades. Versus must use the zero-rank kit. */
data class UpgradeRanks(val height: Int = 0, val speed: Int = 0, val pulse: Int = 0) {
    init {
        require(height in 0..2 && speed in 0..2 && pulse in 0..2) { "Upgrade rank must be between zero and two." }
    }

    val heightMultiplier: Double get() = 1.0 + height * 0.08
    val speedMultiplier: Double get() = 1.0 + speed * 0.08
    val pulseCooldownSeconds: Double get() = 6.0 - pulse * 0.5
    val spentCredits: Int get() = cost(height) + cost(speed) + cost(pulse)

    fun rank(track: UpgradeTrack): Int = when (track) {
        UpgradeTrack.HEIGHT -> height
        UpgradeTrack.SPEED -> speed
        UpgradeTrack.PULSE -> pulse
    }

    fun nextCost(track: UpgradeTrack): Int {
        val current = rank(track)
        require(current < 2) { "This upgrade is already at maximum rank." }
        return current + 1
    }

    internal fun increment(track: UpgradeTrack): UpgradeRanks = when (track) {
        UpgradeTrack.HEIGHT -> copy(height = height + 1)
        UpgradeTrack.SPEED -> copy(speed = speed + 1)
        UpgradeTrack.PULSE -> copy(pulse = pulse + 1)
    }

    private fun cost(rank: Int): Int = rank * (rank + 1) / 2
}

data class GameSettings(
    val musicVolume: Float = 0.4f,
    val effectsVolume: Float = 0.8f,
    val muted: Boolean = false,
    val haptics: Boolean = true,
    val reducedMotion: Boolean = false,
    val screenShake: Boolean = true,
    val highContrast: Boolean = false,
    val largeText: Boolean = false,
    val lowEffects: Boolean = false,
    val highRefresh: Boolean = false,
) {
    init {
        require(musicVolume.isFinite() && musicVolume in 0f..1f) { "Music volume must be between zero and one." }
        require(effectsVolume.isFinite() && effectsVolume in 0f..1f) { "Effects volume must be between zero and one." }
    }
}

/**
 * A complete committed campaign snapshot. Credits and unlocks are derived, so a
 * victory cannot persist its reward separately from the medals which earned it.
 * A building's stable ID is present only after a win. Scores and medals are
 * defensively copied, including when callers use copy().
 */
class CampaignSave(
    bestMedals: List<Int> = listOf(0, 0, 0, 0),
    val upgrades: UpgradeRanks = UpgradeRanks(),
    val tutorialSeen: Boolean = false,
    val settings: GameSettings = GameSettings(),
    brickBestScores: Map<String, Int> = emptyMap(),
) {
    val bestMedals: List<Int> = Collections.unmodifiableList(ArrayList(bestMedals))
    val brickBestScores: Map<String, Int> = Collections.unmodifiableMap(LinkedHashMap(brickBestScores))

    init {
        require(this.bestMedals.size == ENCOUNTER_COUNT) { "A chapter has four encounters." }
        require(this.bestMedals.all { it in 0..3 }) { "Each encounter has zero to three medals." }
        require((1 until ENCOUNTER_COUNT).all { this.bestMedals[it] == 0 || this.bestMedals[it - 1] > 0 }) {
            "Encounters must be completed in chapter order."
        }
        require(upgrades.spentCredits <= this.bestMedals.sum()) { "Upgrade spending exceeds earned medals." }
        require(this.brickBestScores.all { (id, score) -> id.isNotBlank() && score > 0 }) {
            "Building records require a nonblank ID and a positive winning score."
        }
    }

    val credits: Int get() = bestMedals.sum() - upgrades.spentCredits
    val isComplete: Boolean get() = bestMedals.last() > 0

    fun isUnlocked(encounter: Int): Boolean {
        require(encounter in 0 until ENCOUNTER_COUNT) { "Unknown encounter." }
        return encounter == 0 || bestMedals[encounter - 1] > 0
    }

    fun isBuildingUnlocked(id: String, displayOrder: List<String> = BrickBuildings.ids): Boolean {
        require(id in displayOrder) { "Unknown building." }
        return id in brickBestScores || id == displayOrder.firstOrNull { it !in brickBestScores }
    }

    fun copy(
        bestMedals: List<Int> = this.bestMedals,
        upgrades: UpgradeRanks = this.upgrades,
        tutorialSeen: Boolean = this.tutorialSeen,
        settings: GameSettings = this.settings,
        brickBestScores: Map<String, Int> = this.brickBestScores,
    ): CampaignSave = CampaignSave(bestMedals, upgrades, tutorialSeen, settings, brickBestScores)

    override fun equals(other: Any?): Boolean = other is CampaignSave &&
        bestMedals == other.bestMedals && upgrades == other.upgrades &&
        tutorialSeen == other.tutorialSeen && settings == other.settings && brickBestScores == other.brickBestScores

    override fun hashCode(): Int {
        var result = bestMedals.hashCode()
        result = 31 * result + upgrades.hashCode()
        result = 31 * result + tutorialSeen.hashCode()
        result = 31 * result + settings.hashCode()
        return 31 * result + brickBestScores.hashCode()
    }

    override fun toString(): String = "CampaignSave(bestMedals=$bestMedals, upgrades=$upgrades, tutorialSeen=$tutorialSeen, settings=$settings, brickBestScores=$brickBestScores)"

    companion object {
        const val ENCOUNTER_COUNT = 4
    }
}

object CampaignProgression {
    /** Call only for a completed solo victory. Defeat and abandoned matches have no transaction. */
    fun recordVictory(save: CampaignSave, encounter: Int, conceded: Int): CampaignSave {
        require(save.isUnlocked(encounter)) { "Encounter is locked." }
        val isBoss = encounter == CampaignSave.ENCOUNTER_COUNT - 1
        require(conceded in 0..if (isBoss) 2 else 4) { "Conceded goals are incompatible with a victory." }
        val medals = when {
            conceded == 0 -> 3
            conceded <= if (isBoss) 1 else 2 -> 2
            else -> 1
        }
        if (medals <= save.bestMedals[encounter]) return save
        val improved = save.bestMedals.toMutableList()
        improved[encounter] = medals
        return save.copy(bestMedals = improved)
    }

    fun purchase(save: CampaignSave, track: UpgradeTrack): CampaignSave {
        require(save.credits >= save.upgrades.nextCost(track)) { "Not enough earned credits." }
        return save.copy(upgrades = save.upgrades.increment(track))
    }

    fun respec(save: CampaignSave): CampaignSave = save.copy(upgrades = UpgradeRanks())

    fun recordBrickVictory(save: CampaignSave, id: String, score: Int): CampaignSave {
        require(save.isBuildingUnlocked(id)) { "Building is locked." }
        require(score > 0) { "Clearing a building must earn a positive score." }
        if (score <= (save.brickBestScores[id] ?: 0)) return save
        return save.copy(brickBestScores = save.brickBestScores + (id to score))
    }

    fun resetClassic(save: CampaignSave): CampaignSave = CampaignSave(settings = save.settings, brickBestScores = save.brickBestScores)

    fun resetBricks(save: CampaignSave): CampaignSave = save.copy(brickBestScores = emptyMap())
}
