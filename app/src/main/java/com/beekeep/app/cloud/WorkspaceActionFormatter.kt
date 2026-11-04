package com.beekeep.app.cloud

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Presentation-ready output for one Workspace Actions row, independent of Compose. */
internal data class WorkspaceActionPresentation(
    val header: String,
    val location: String?,
    val changes: List<WorkspaceActionChangePresentation>,
    val affectedHiveNames: List<String>,
    val affectedHiveCount: Int?,
    val legacyDescription: String?,
    val accessibilityDescription: String,
    val isLegacy: Boolean
)

internal data class WorkspaceActionChangePresentation(
    val field: String,
    val label: String,
    val oldValue: String,
    val newValue: String,
    val oldPreview: String,
    val newPreview: String,
    val isExpandable: Boolean
)

/**
 * One formatter owns action wording, field labels, timestamp localization, value units,
 * long-value previews, and the complete TalkBack sentence used by the Actions UI.
 * Timestamp and treatment-date output is localized using the caller's device locale and zone.
 * Full field values remain separate from the short previews so expanded text and TalkBack never lose detail.
 */
internal object WorkspaceActionFormatter {
    const val MAX_PREVIEW_LENGTH = 80
    private const val ELLIPSIS = "…"

    fun format(
        entry: BeeKeepWorkspaceActionLogEntry,
        locale: Locale = Locale.getDefault(),
        zoneId: ZoneId = ZoneId.systemDefault()
    ): WorkspaceActionPresentation {
        val legacy = entry.structured_version < 1
        val action = actionLabel(entry, legacy)
        val time = formatTimestamp(entry.action_at, locale, zoneId)
        val username = entry.username.trim().removePrefix("@").ifBlank { "Team member" }
        val header = "@$username · $action · $time"
        val location = if (legacy) null else formatLocation(entry)
        val changes = if (legacy) emptyList() else formatChanges(entry.changes, locale, zoneId)
        val hiveNames = if (legacy) emptyList() else entry.affected_hives_snapshot
            .map { formatHiveName(it.name) }
            .filter(String::isNotBlank)
        val hiveCount = if (legacy) null else entry.affected_hive_count
            ?: entry.affected_hive_ids.size.takeIf { it > 0 }
            ?: hiveNames.size.takeIf { it > 0 }
        val legacyDescription = entry.description.takeIf { legacy && it.isNotBlank() }

        return WorkspaceActionPresentation(
            header = header,
            location = location,
            changes = changes,
            affectedHiveNames = hiveNames,
            affectedHiveCount = hiveCount,
            legacyDescription = legacyDescription,
            accessibilityDescription = accessibilitySentence(
                username, action, time, location, changes, hiveNames, legacyDescription
            ),
            isLegacy = legacy
        )
    }

    fun preview(value: String): String =
        if (value.length <= MAX_PREVIEW_LENGTH) value
        else value.take(MAX_PREVIEW_LENGTH - 1).trimEnd() + ELLIPSIS

    private fun actionLabel(entry: BeeKeepWorkspaceActionLogEntry, legacy: Boolean): String {
        val keys = entry.changes.mapTo(mutableSetOf()) { it.field }
        return when (entry.action_type) {
            "apiary_added" -> "Added apiary"
            "apiary_edited" -> if (!legacy && "name" in keys) "Renamed apiary" else "Edited apiary"
            "apiary_deleted" -> "Deleted apiary"
            "hive_added" -> "Added hive"
            "hive_edited" -> when {
                !legacy && "apiary" in keys -> "Moved hive"
                !legacy && "number" in keys -> "Renamed hive"
                else -> "Edited hive"
            }
            "hive_deleted" -> "Deleted hive"
            "inspection_created" -> "Added inspection"
            "inspection_edited" -> "Edited inspection"
            "feeding_added" -> "Added feeding"
            "feeding_edited" -> "Edited feeding"
            "treatment_added" -> "Added treatment"
            "treatment_edited" -> "Edited treatment"
            "harvest_added" -> "Added harvest"
            "harvest_edited" -> "Edited harvest"
            "mass_feed" -> "Applied feed"
            "mass_treatment" -> "Applied treatment"
            "mass_harvest" -> "Applied harvest"
            else -> if (legacy) "Workspace activity" else humanize(entry.action_type)
        }
    }

    private fun formatLocation(entry: BeeKeepWorkspaceActionLogEntry): String? {
        val apiary = entry.apiary_name_snapshot
            ?.trim()?.takeIf(String::isNotEmpty)?.let(::formatApiaryName)
        val hive = entry.hive_name_snapshot
            ?.trim()?.takeIf(String::isNotEmpty)?.let(::formatHiveName)
        val batch = entry.target_type == "batch" ||
            entry.action_type in setOf("mass_feed", "mass_treatment", "mass_harvest")

        if (batch) {
            val count = entry.affected_hive_count
                ?: entry.affected_hive_ids.size.takeIf { it > 0 }
                ?: entry.affected_hives_snapshot.size
            val countText = if (count > 0) {
                count.toString() + if (count == 1) " hive" else " hives"
            } else null
            return listOfNotNull(apiary, countText).joinToString(" > ").ifBlank { null }
        }
        return listOfNotNull(apiary, hive).joinToString(" > ").ifBlank { null }
    }

    private fun formatChanges(
        source: List<BeeKeepWorkspaceActionChange>,
        locale: Locale,
        zoneId: ZoneId
    ): List<WorkspaceActionChangePresentation> {
        if (source.isEmpty()) return emptyList()
        val byField = source.associateBy { it.field }
        val unitChange = byField["unit"]
        val weightUnitChange = byField["weight_unit"]

        return source.mapNotNull { change ->
            if (change.field == "unit" && "amount" in byField) return@mapNotNull null
            val weightFields = setOf("wet_honey_weight", "dry_honey_weight", "wax_weight", "propolis_weight")
            if (change.field == "weight_unit" && weightFields.any { it in byField }) {
                return@mapNotNull null
            }
            val unit = when (change.field) {
                "amount" -> unitChange
                in weightFields -> weightUnitChange
                else -> null
            }
            val oldUnit = unit?.old_value?.let(::plainValue)
            val newUnit = unit?.new_value?.let(::plainValue)
            val oldText = formatFieldValue(change.field, change.old_value, oldUnit, locale, zoneId)
            val newText = formatFieldValue(change.field, change.new_value, newUnit, locale, zoneId)
            WorkspaceActionChangePresentation(
                field = change.field,
                label = fieldLabel(change.field),
                oldValue = oldText,
                newValue = newText,
                oldPreview = preview(oldText),
                newPreview = preview(newText),
                isExpandable = oldText.length > MAX_PREVIEW_LENGTH || newText.length > MAX_PREVIEW_LENGTH
            )
        }
    }

    private fun formatFieldValue(
        field: String,
        element: JsonElement?,
        unit: String? = null,
        locale: Locale,
        zoneId: ZoneId
    ): String {
        if (element == null || element == JsonNull) return "(empty)"
        val raw = when (element) {
            is JsonObject -> {
                val value = element["value"] ?: element["amount"] ?: element["count"]
                val nestedUnit = element["unit"]?.let(::plainValue) ?: unit
                return formatFieldValue(field, value, nestedUnit, locale, zoneId)
            }
            is JsonArray -> element.joinToString(", ") { plainValue(it) }
            is JsonPrimitive -> when {
                element.isString -> element.contentOrNull.orEmpty().trim().ifBlank { "(empty)" }
                element.content == "true" -> "Yes"
                element.content == "false" -> "No"
                else -> element.doubleOrNull?.let(::formatNumber) ?: element.content
            }
            else -> element.toString()
        }
        if (raw == "(empty)") return raw
        if (field in setOf("inserted_at", "removal_at", "withdrawal_until")) {
            formatDateValue(raw, locale, zoneId)?.let { return it }
        }

        val value = when (field) {
            "strength" -> strengthLabel(raw)
            "queen_status", "feed_type", "treatment_type", "status", "queen_mark_color",
            "queen_origin", "queen_temperament", "disease_flags" -> humanize(raw)
            else -> raw
        }
        val suffix = when (field) {
            "mite_count" -> if (value == "1") " mite" else " mites"
            "sample_size" -> if (value == "1") " bee" else " bees"
            "bee_count_frames", "open_brood", "capped_brood", "honey_stores",
            "pollen", "empty_drawn_comb" -> " frames"
            "supers_pulled" -> if (value == "1") " super" else " supers"
            "mite_percent" -> "%"
            "queen_age_months" -> if (value == "1") " month" else " months"
            else -> ""
        }
        val displayed = value + suffix
        return if (!unit.isNullOrBlank() && field in setOf(
                "amount", "wet_honey_weight", "dry_honey_weight", "wax_weight", "propolis_weight"
            )
        ) "$displayed ${unit.trim()}" else displayed
    }

    private fun fieldLabel(field: String): String = when (field) {
        "name" -> "Name"
        "number" -> "Hive name"
        "notes" -> "Notes"
        "forage_notes" -> "Forage notes"
        "water_notes" -> "Water notes"
        "status" -> "Status"
        "queen_status" -> "Queen status"
        "queen_mark_color" -> "Queen mark color"
        "queen_origin" -> "Queen origin"
        "queen_age_months" -> "Queen age"
        "queen_temperament" -> "Queen temperament"
        "strength" -> "Strength"
        "mite_percent" -> "Mite level"
        "mite_count" -> "Mite count"
        "sample_size" -> "Sample size"
        "eggs" -> "Eggs"
        "open_brood" -> "Open brood"
        "capped_brood" -> "Capped brood"
        "honey_stores" -> "Honey stores"
        "pollen" -> "Pollen"
        "empty_drawn_comb" -> "Empty drawn comb"
        "emergency_cells" -> "Emergency cells"
        "supercedure_cells" -> "Supersedure cells"
        "swarm_cells" -> "Swarm cells"
        "disease_flags" -> "Disease flags"
        "bee_count_frames" -> "Bee count"
        "feed_type" -> "Feed type"
        "ratio" -> "Mix ratio"
        "amount" -> "Amount"
        "unit" -> "Unit"
        "treatment_type" -> "Treatment"
        "product" -> "Product"
        "inserted_at" -> "Inserted"
        "removal_at" -> "Removal"
        "withdrawal_until" -> "Withdrawal until"
        "supers_pulled" -> "Supers pulled"
        "wet_honey_weight" -> "Wet honey"
        "dry_honey_weight" -> "Dry honey"
        "weight_unit" -> "Weight unit"
        "wax_weight" -> "Wax weight"
        "propolis_weight" -> "Propolis weight"
        "apiary" -> "Apiary"
        else -> humanize(field)
    }

    private fun accessibilitySentence(
        username: String,
        action: String,
        time: String,
        location: String?,
        changes: List<WorkspaceActionChangePresentation>,
        hives: List<String>,
        legacyDescription: String?
    ): String = buildString {
        append(username)
        append(' ')
        append(action.lowercase(Locale.ROOT))
        if (!location.isNullOrBlank()) {
            append(" at ")
            append(location)
        }
        append(" on ")
        append(time)
        append('.')
        if (!legacyDescription.isNullOrBlank()) {
            append(' ')
            append(legacyDescription.trim())
            if (!legacyDescription.trim().endsWith('.')) append('.')
        } else {
            changes.forEach { change ->
                append(' ')
                append(change.label)
                append(" changed from ")
                append(change.oldValue)
                append(" to ")
                append(change.newValue)
                append('.')
            }
        }
        if (hives.isNotEmpty()) {
            append(" Affected hives: ")
            append(hives.joinToString(", "))
            append('.')
        }
    }

    private fun formatTimestamp(value: String, locale: Locale, zoneId: ZoneId): String {
        val instant = parseTimestamp(value, zoneId) ?: return value.replace('T', ' ').take(32)
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zoneId)
            .format(instant)
    }

    private fun formatDateValue(value: String, locale: Locale, zoneId: ZoneId): String? {
        val instant = parseTimestamp(value, zoneId) ?: return null
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(zoneId)
            .format(instant)
    }

    private fun parseTimestamp(value: String, zoneId: ZoneId): Instant? {
        val raw = value.trim()
        if (raw.isEmpty()) return null
        return try {
            Instant.parse(raw)
        } catch (_: DateTimeParseException) {
            try {
                OffsetDateTime.parse(raw).toInstant()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDateTime.parse(raw).atZone(zoneId).toInstant()
                } catch (_: DateTimeParseException) {
                    try {
                        LocalDate.parse(raw).atStartOfDay(zoneId).toInstant()
                    } catch (_: DateTimeParseException) {
                        null
                    }
                }
            }
        }
    }

    private fun plainValue(value: JsonElement): String = when (value) {
        JsonNull -> ""
        is JsonPrimitive -> value.content
        is JsonArray -> value.joinToString(", ") { plainValue(it) }
        is JsonObject -> value["value"]?.let(::plainValue).orEmpty()
        else -> value.toString()
    }

    private fun strengthLabel(value: String): String = when (value.trim()) {
        "2" -> "Very weak"
        "4" -> "Weak"
        "6" -> "Moderate"
        "8" -> "Strong"
        "10" -> "Very strong"
        else -> humanize(value)
    }

    private fun formatApiaryName(value: String): String =
        if (value.startsWith("apiary ", ignoreCase = true)) value else "Apiary $value"

    private fun formatHiveName(value: String): String =
        if (value.startsWith("hive ", ignoreCase = true)) value else "Hive $value"

    private fun humanize(value: String): String =
        value.replace('_', ' ').trim()
            .split(Regex("\\s+"))
            .filter(String::isNotEmpty)
            .joinToString(" ") { word ->
                word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
            }

    private fun formatNumber(value: Double): String =
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}
