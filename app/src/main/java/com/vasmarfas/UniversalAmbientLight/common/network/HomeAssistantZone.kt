package com.vasmarfas.UniversalAmbientLight.common.network

/**
 * Зона экрана, чей усреднённый цвет уходит на лампу Home Assistant. Порядок констант —
 * контракт с [com.vasmarfas.UniversalAmbientLight.common.util.ZoneColorExtractor]: тот
 * раскладывает цвета в массив по ordinal.
 */
enum class HomeAssistantZone {
    AVERAGE,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT;

    companion object {
        fun fromStored(value: String): HomeAssistantZone? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * Лампа из Home Assistant, привязанная к зоне экрана. Список хранится одной строкой в
 * настройках: по лампе на строку, поля разделены табуляцией — `entity_id⇥зона⇥имя`.
 */
data class HomeAssistantLamp(
    val entityId: String,
    val zone: HomeAssistantZone,
    val name: String,
) {

    companion object {
        private const val FIELD_SEPARATOR = '\t'

        fun parseList(stored: String?): List<HomeAssistantLamp> {
            if (stored.isNullOrBlank()) return emptyList()
            return stored.lineSequence().mapNotNull { line ->
                val fields = line.split(FIELD_SEPARATOR)
                if (fields.size < 2) return@mapNotNull null
                val entityId = fields[0].trim()
                if (entityId.isEmpty()) return@mapNotNull null
                val zone = HomeAssistantZone.fromStored(fields[1].trim())
                    ?: return@mapNotNull null
                HomeAssistantLamp(entityId, zone, fields.getOrElse(2) { "" }.trim())
            }.toList()
        }

        fun serialize(lamps: List<HomeAssistantLamp>): String {
            return lamps.joinToString("\n") { lamp ->
                // Разделители вычищаются из имени, иначе строка развалится при разборе
                val safeName = lamp.name.replace(FIELD_SEPARATOR, ' ').replace('\n', ' ')
                "${lamp.entityId}$FIELD_SEPARATOR${lamp.zone.name.lowercase()}" +
                        "$FIELD_SEPARATOR$safeName"
            }
        }
    }
}
