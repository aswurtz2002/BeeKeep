package com.beekeep.app.cloud

import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceActionFormatterTest {
    private val utc = ZoneId.of("UTC")
    private val locale = Locale.US

    @Test
    fun formatsHeaderAndActionTimeInSuppliedLocalZone() {
        val result = WorkspaceActionFormatter.format(
            entry(
                username = "@beekeeper_1",
                action_type = "hive_added",
                action_at = "2026-11-04T18:30:00Z",
                apiary_name_snapshot = "North Yard",
                hive_name_snapshot = "12"
            ),
            locale,
            ZoneId.of("America/Regina")
        )

        assertTrue(result.header.startsWith("@beekeeper_1 · Added hive · "))
        assertTrue(result.header.contains("11/4/26, 12:30 PM"))
        assertEquals("Apiary North Yard > Hive 12", result.location)
        assertFalse(result.isLegacy)
    }

    @Test
    fun distinguishesMoveRenameDeleteAndMassApplyVerbs() {
        val moved = WorkspaceActionFormatter.format(
            entry(
                action_type = "hive_edited",
                changes = listOf(change("apiary", "Old Yard", "New Yard"))
            ),
            locale,
            utc
        )
        val renamed = WorkspaceActionFormatter.format(
            entry(
                action_type = "apiary_edited",
                changes = listOf(change("name", "Old", "New"))
            ),
            locale,
            utc
        )
        val renamedHive = WorkspaceActionFormatter.format(
            entry(
                action_type = "hive_edited",
                changes = listOf(change("number", "12", "Queen 12"))
            ),
            locale,
            utc
        )
        val deleted = WorkspaceActionFormatter.format(entry(action_type = "hive_deleted"), locale, utc)
        val applied = WorkspaceActionFormatter.format(entry(action_type = "mass_feed"), locale, utc)

        assertTrue(moved.header.contains("Moved hive"))
        assertTrue(renamed.header.contains("Renamed apiary"))
        assertTrue(renamedHive.header.contains("Renamed hive"))
        assertTrue(deleted.header.contains("Deleted hive"))
        assertTrue(applied.header.contains("Applied feed"))
    }

    @Test
    fun formatsMassActionSummaryAndExpandableHiveSnapshots() {
        val result = WorkspaceActionFormatter.format(
            entry(
                action_type = "mass_treatment",
                target_type = "batch",
                apiary_name_snapshot = "South",
                affected_hive_count = 2,
                affected_hive_ids = listOf(10, 11),
                affected_hives_snapshot = listOf(
                    BeeKeepWorkspaceAffectedHiveSnapshot(10, "10"),
                    BeeKeepWorkspaceAffectedHiveSnapshot(11, "11")
                )
            ),
            locale,
            utc
        )

        assertEquals("Apiary South > 2 hives", result.location)
        assertEquals(listOf("Hive 10", "Hive 11"), result.affectedHiveNames)
        assertEquals(2, result.affectedHiveCount)
        assertTrue(result.accessibilityDescription.contains("Affected hives: Hive 10, Hive 11"))
    }

    @Test
    fun formatsFieldLabelsStrengthGradesEmptyValuesAndUnits() {
        val result = WorkspaceActionFormatter.format(
            entry(
                action_type = "feeding_added",
                changes = listOf(
                    change("strength", 2, 8),
                    change("queen_status", "not_spotted", "spotted"),
                    change("amount", null, 2.5),
                    change("unit", null, "L"),
                    change("notes", null, "Field note")
                )
            ),
            locale,
            utc
        )

        assertEquals("Strength", result.changes[0].label)
        assertEquals("Very weak", result.changes[0].oldValue)
        assertEquals("Strong", result.changes[0].newValue)
        assertEquals("Queen status", result.changes[1].label)
        assertEquals("Not Spotted", result.changes[1].oldValue)
        assertEquals("Amount", result.changes[2].label)
        assertEquals("(empty)", result.changes[2].oldValue)
        assertEquals("2.5 L", result.changes[2].newValue)
        assertTrue(result.accessibilityDescription.contains("Strength changed from Very weak to Strong"))
    }

    @Test
    fun addsFieldUnitsToInspectionAndHarvestQuantities() {
        val result = WorkspaceActionFormatter.format(
            entry(
                action_type = "harvest_added",
                changes = listOf(
                    change("wet_honey_weight", null, 18.5),
                    change("weight_unit", null, "lb"),
                    change("mite_count", null, 2),
                    change("sample_size", null, 300),
                    change("bee_count_frames", null, 4.5)
                )
            ),
            locale,
            utc
        )

        assertEquals("18.5 lb", result.changes.first { it.field == "wet_honey_weight" }.newValue)
        assertEquals("2 mites", result.changes.first { it.field == "mite_count" }.newValue)
        assertEquals("300 bees", result.changes.first { it.field == "sample_size" }.newValue)
        assertEquals("4.5 frames", result.changes.first { it.field == "bee_count_frames" }.newValue)
    }

    @Test
    fun formatsQueenAgeUnitsAndTreatmentDatesForTheSelectedLocale() {
        val result = WorkspaceActionFormatter.format(
            entry(
                action_type = "treatment_added",
                changes = listOf(
                    change("queen_age_months", null, 18),
                    change("inserted_at", null, "2026-11-04"),
                    change("removal_at", null, "2026-11-18")
                )
            ),
            locale,
            utc
        )

        assertEquals("18 months", result.changes.first { it.field == "queen_age_months" }.newValue)
        assertEquals("Nov 4, 2026", result.changes.first { it.field == "inserted_at" }.newValue)
        assertEquals("Nov 18, 2026", result.changes.first { it.field == "removal_at" }.newValue)
    }

    @Test
    fun longValuesKeepFullTextAndExposeAnEightyCharacterPreview() {
        val longText = "A".repeat(110)
        val result = WorkspaceActionFormatter.format(
            entry(changes = listOf(change("notes", "old", longText))),
            locale,
            utc
        )
        val note = result.changes.single()

        assertTrue(note.isExpandable)
        assertEquals(WorkspaceActionFormatter.MAX_PREVIEW_LENGTH, note.newPreview.length)
        assertEquals(longText.take(WorkspaceActionFormatter.MAX_PREVIEW_LENGTH - 1) + "…", note.newPreview)
        assertEquals(longText, note.newValue)
    }

    @Test
    fun legacyEntriesUseOnlyTheirExistingDescription() {
        val legacy = entry(
            structured_version = 0,
            description = "Edited hive Hive 8 in Apiary North",
            changes = listOf(change("strength", 2, 10))
        )
        val result = WorkspaceActionFormatter.format(legacy, locale, utc)

        assertTrue(result.isLegacy)
        assertEquals("Edited hive Hive 8 in Apiary North", result.legacyDescription)
        assertTrue(result.changes.isEmpty())
        assertNull(result.location)
        assertTrue(result.accessibilityDescription.contains("Edited hive Hive 8 in Apiary North"))
    }

    @Test
    fun oldPayloadWithoutStructuredFieldsStillDeserializesAsLegacy() {
        val oldJson = """
            {
              "id":"entry-1",
              "workspace_id":"workspace-1",
              "client_action_id":"action-1",
              "username":"beekeeper",
              "action_type":"hive_edited",
              "target_type":"hive",
              "target_id":"12",
              "description":"Edited hive Hive 12 in Apiary North",
              "action_at":"2026-11-04T12:30:00Z",
              "created_at":"2026-11-04T12:30:01Z"
            }
        """.trimIndent()

        val decoded = Json.decodeFromString<BeeKeepWorkspaceActionLogEntry>(oldJson)
        val formatted = WorkspaceActionFormatter.format(decoded, locale, utc)

        assertEquals(0, decoded.structured_version)
        assertTrue(formatted.isLegacy)
        assertEquals("Edited hive Hive 12 in Apiary North", formatted.legacyDescription)
    }

    @Test
    fun invalidTimestampsFallBackWithoutThrowing() {
        val result = WorkspaceActionFormatter.format(
            entry(action_at = "not-a-date"),
            locale,
            utc
        )

        assertTrue(result.header.contains("not-a-date"))
        assertNotNull(result.accessibilityDescription)
    }

    private fun entry(
        username: String = "beekeeper",
        action_type: String = "hive_edited",
        target_type: String = "hive",
        description: String = "Edited hive Hive 12 in Apiary North",
        action_at: String = "2026-11-04T12:30:00Z",
        structured_version: Int = 1,
        apiary_name_snapshot: String? = "North",
        hive_name_snapshot: String? = "12",
        changes: List<BeeKeepWorkspaceActionChange> = emptyList(),
        affected_hive_count: Int? = null,
        affected_hive_ids: List<Long> = emptyList(),
        affected_hives_snapshot: List<BeeKeepWorkspaceAffectedHiveSnapshot> = emptyList()
    ) = BeeKeepWorkspaceActionLogEntry(
        id = "entry-1",
        workspace_id = "workspace-1",
        client_action_id = "action-1",
        username = username,
        action_type = action_type,
        target_type = target_type,
        target_id = "12",
        description = description,
        action_at = action_at,
        created_at = action_at,
        structured_version = structured_version,
        apiary_name_snapshot = apiary_name_snapshot,
        hive_name_snapshot = hive_name_snapshot,
        changes = changes,
        affected_hive_count = affected_hive_count,
        affected_hive_ids = affected_hive_ids,
        affected_hives_snapshot = affected_hives_snapshot
    )

    private fun change(field: String, oldValue: Any?, newValue: Any?) =
        BeeKeepWorkspaceActionChange(
            field = field,
            old_value = oldValue.toJsonElement(),
            new_value = newValue.toJsonElement()
        )

    private fun Any?.toJsonElement() = when (this) {
        null -> JsonNull
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }
}
