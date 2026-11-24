package io.github.lubacareer.spacepong.progression

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Data-only JSON; no reflective or polymorphic object deserialization. */
internal object SaveCodec {
    private const val CURRENT_VERSION = 3

    // Frozen mapping from the released v2 format. Never derive it from current display order.
    private val v2BuildingOrder = listOf("outpost", "hangar", "twin_towers", "observatory", "launch_gantry", "orbital_citadel")

    fun encode(save: CampaignSave): String = buildJsonObject {
        put("version", CURRENT_VERSION)
        put("bestMedals", JsonArray(save.bestMedals.map { JsonPrimitive(it) }))
        put("brickBestScores", JsonObject(save.brickBestScores.mapValues { JsonPrimitive(it.value) }))
        put(
            "upgrades",
            buildJsonObject {
                put("height", save.upgrades.height)
                put("speed", save.upgrades.speed)
                put("pulse", save.upgrades.pulse)
            },
        )
        put("tutorialSeen", save.tutorialSeen)
        put(
            "settings",
            buildJsonObject {
                put("musicVolume", save.settings.musicVolume)
                put("effectsVolume", save.settings.effectsVolume)
                put("muted", save.settings.muted)
                put("haptics", save.settings.haptics)
                put("reducedMotion", save.settings.reducedMotion)
                put("screenShake", save.settings.screenShake)
                put("highContrast", save.settings.highContrast)
                put("largeText", save.settings.largeText)
                put("lowEffects", save.settings.lowEffects)
                put("highRefresh", save.settings.highRefresh)
            },
        )
    }.toString()

    fun decode(text: String): CampaignSave {
        val root = Json.parseToJsonElement(text) as? JsonObject
            ?: throw IllegalArgumentException("Save must contain a JSON object.")
        val version = root.requiredInt("version")
        if (version > CURRENT_VERSION) throw UnsupportedSaveVersionException(version)
        require(version in 1..CURRENT_VERSION) { "Unsupported legacy save version." }
        val medals = root["bestMedals"] as? JsonArray
            ?: throw IllegalArgumentException("Missing best medals.")
        val ranks = root["upgrades"] as? JsonObject
            ?: throw IllegalArgumentException("Missing upgrade ranks.")
        val settings = when (val value = root["settings"]) {
            null -> GameSettings()
            is JsonObject -> decodeSettings(value)
            else -> throw IllegalArgumentException("Settings must be an object.")
        }
        return CampaignSave(
            bestMedals = medals.map { it.requireInt("Medal") },
            upgrades = UpgradeRanks(
                ranks.requiredInt("height"),
                ranks.requiredInt("speed"),
                ranks.requiredInt("pulse"),
            ),
            tutorialSeen = root.optionalBoolean("tutorialSeen", false),
            settings = settings,
            brickBestScores = decodeBuildingScores(version, root["brickBestScores"]),
        )
    }

    private fun decodeBuildingScores(version: Int, value: JsonElement?): Map<String, Int> {
        if (version == 1) return emptyMap()
        if (version == 2) {
            val array = value as? JsonArray ?: throw IllegalArgumentException("Missing v2 building scores.")
            val scores = array.map { it.requireInt("Building score") }
            require(scores.size == v2BuildingOrder.size && scores.all { it >= 0 }) { "Invalid v2 building scores." }
            require(scores.dropWhile { it > 0 }.all { it == 0 }) { "Invalid v2 building sequence." }
            return v2BuildingOrder.zip(scores).toMap().filterValues { it > 0 }
        }
        val records = value as? JsonObject ?: throw IllegalArgumentException("Missing building records.")
        return records.mapValues { it.value.requireInt("Building score") }
    }

    private fun decodeSettings(root: JsonObject): GameSettings {
        val defaults = GameSettings()
        return GameSettings(
            musicVolume = root.optionalFloat("musicVolume", defaults.musicVolume),
            effectsVolume = root.optionalFloat("effectsVolume", defaults.effectsVolume),
            muted = root.optionalBoolean("muted", defaults.muted),
            haptics = root.optionalBoolean("haptics", defaults.haptics),
            reducedMotion = root.optionalBoolean("reducedMotion", defaults.reducedMotion),
            screenShake = root.optionalBoolean("screenShake", defaults.screenShake),
            highContrast = root.optionalBoolean("highContrast", defaults.highContrast),
            largeText = root.optionalBoolean("largeText", defaults.largeText),
            lowEffects = root.optionalBoolean("lowEffects", defaults.lowEffects),
            highRefresh = root.optionalBoolean("highRefresh", defaults.highRefresh),
        )
    }

    private fun JsonObject.requiredInt(key: String): Int = get(key)?.requireInt(key) ?: throw IllegalArgumentException("Missing $key.")

    private fun JsonElement.requireInt(label: String): Int {
        val primitive = this as? JsonPrimitive
            ?: throw IllegalArgumentException("$label must be an integer.")
        require(!primitive.isString) { "$label must be a JSON number." }
        return primitive.intOrNull ?: throw IllegalArgumentException("$label must be an integer.")
    }

    private fun JsonObject.optionalBoolean(key: String, default: Boolean): Boolean {
        val value = get(key) ?: return default
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("$key must be a boolean.")
        require(!primitive.isString) { "$key must be a JSON boolean." }
        return primitive.booleanOrNull ?: throw IllegalArgumentException("$key must be a boolean.")
    }

    private fun JsonObject.optionalFloat(key: String, default: Float): Float {
        val value = get(key) ?: return default
        val primitive = value as? JsonPrimitive
            ?: throw IllegalArgumentException("$key must be a number.")
        require(!primitive.isString) { "$key must be a JSON number." }
        return primitive.floatOrNull ?: throw IllegalArgumentException("$key must be a number.")
    }
}
