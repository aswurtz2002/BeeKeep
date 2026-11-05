package com.beekeep.app.cloud

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class LocalDataAttachmentMergePlannerTest {
    private fun snapshot(vararg rows: Pair<String, JSONObject>, tasks: JSONArray = JSONArray()): String {
        val records = JSONArray()
        rows.forEach { (type, payload) ->
            records.put(
                JSONObject()
                    .put("type", type)
                    .put("id", payload.getLong("id"))
                    .put("updatedAt", payload.optLong("updated_at", 100L))
                    .put("payload", payload)
            )
        }
        return JSONObject()
            .put("format", "beekeep-local-attachment-snapshot")
            .put("schemaVersion", 1)
            .put("targetAccountId", "account-a")
            .put("records", records)
            .put("tasks", tasks)
            .put("inspectionNoteDraftPreferences", JSONArray().put(JSONObject().put("key", "personal_hive_5")))
            .put("inspectionFormDraftPreferences", JSONArray())
            .toString()
    }

    private fun cloud(type: String, id: Long, payload: JSONObject, deleted: Boolean = false) =
        CloudDocumentRow(
            user_id = "account-a",
            entity_type = type,
            entity_id = id,
            payload = payload.toString(),
            updated_at = payload.optLong("updated_at", 100L),
            deleted = deleted
        )

    private fun apiary(id: Long, name: String, notes: String = "") =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("notes", notes)
            .put("updated_at", 100L)
            .put("deleted", false)

    private fun hive(id: Long, number: String, apiary: String, apiaryId: Long? = null) =
        JSONObject()
            .put("id", id)
            .put("number", number)
            .put("apiary", apiary)
            .put("apiary_id", apiaryId ?: JSONObject.NULL)
            .put("queen_status", "Laying")
            .put("queen_mark_color", "")
            .put("queen_origin", "")
            .put("queen_age_months", JSONObject.NULL)
            .put("queen_temperament", 3)
            .put("strength", 5)
            .put("mite_percent", 0.0)
            .put("tag_uid", JSONObject.NULL)
            .put("updated_at", 100L)
            .put("deleted", false)
            .put("status", "ACTIVE")
            .put("dead_at", JSONObject.NULL)
            .put("status_changed_at", 0L)
            .put("latitude", JSONObject.NULL)
            .put("longitude", JSONObject.NULL)
            .put("bee_count_frames", JSONObject.NULL)

    private fun feeding(id: Long, hiveId: Long, uuid: String, amount: Double = 2.0) =
        JSONObject()
            .put("id", id)
            .put("hive_id", hiveId)
            .put("created_at", 200L)
            .put("updated_at", 200L)
            .put("record_uuid", uuid)
            .put("apiary_id", JSONObject.NULL)
            .put("batch_id", JSONObject.NULL)
            .put("occurred_at", 200L)
            .put("created_by", JSONObject.NULL)
            .put("device_id", "device-a")
            .put("feed_type", "syrup")
            .put("ratio", "1:1")
            .put("amount", amount)
            .put("unit", "L")
            .put("notes", "")

    @Test
    fun remapsApiaryCollisionAndCarriesMappedParentIdToHive() {
        val localApiary = apiary(10L, "North Yard", "local notes")
        val localHive = hive(20L, "H-1", "North Yard", 10L)
        val remoteApiary = apiary(10L, "South Yard", "remote notes")

        val plan = LocalDataAttachmentMergePlanner.plan(
            snapshot("apiary" to localApiary, "hive" to localHive),
            listOf(cloud("apiary", 10L, remoteApiary))
        )

        val apiaryItem = plan.items.single { it.entityType == "apiary" }
        val hiveItem = plan.items.single { it.entityType == "hive" }
        assertEquals(LocalDataAttachmentDisposition.REMAP_ID, apiaryItem.disposition)
        assertNotEquals(10L, apiaryItem.targetId)
        assertEquals(LocalDataAttachmentDisposition.UPLOAD_NEW, hiveItem.disposition)
        assertEquals(apiaryItem.targetId, hiveItem.targetApiaryId)
        assertEquals(0, plan.reviewRequiredCount)
    }

    @Test
    fun remapsHiveCollisionAndCarriesMappedHiveIdToHistory() {
        val localHive = hive(20L, "LOCAL-1", "Yard")
        val remoteHive = hive(20L, "REMOTE-1", "Other Yard")
        val localFeeding = feeding(30L, 20L, "local-feed-uuid")

        val plan = LocalDataAttachmentMergePlanner.plan(
            snapshot("hive" to localHive, "feeding" to localFeeding),
            listOf(cloud("hive", 20L, remoteHive))
        )

        val hiveItem = plan.items.single { it.entityType == "hive" }
        val feedingItem = plan.items.single { it.entityType == "feeding" }
        assertEquals(LocalDataAttachmentDisposition.REMAP_ID, hiveItem.disposition)
        assertNotEquals(20L, hiveItem.targetId)
        assertEquals(hiveItem.targetId, feedingItem.targetHiveId)
        assertEquals(LocalDataAttachmentDisposition.UPLOAD_NEW, feedingItem.disposition)
    }

    @Test
    fun sameStableHistoryUuidIsDeduplicatedEvenWhenNumericIdsDiffer() {
        val localHive = hive(20L, "H-1", "Yard")
        val remoteHive = hive(20L, "H-1", "Yard")
        val localFeeding = feeding(30L, 20L, "feed-uuid-1")
        val remoteFeeding = feeding(900L, 20L, "feed-uuid-1")

        val plan = LocalDataAttachmentMergePlanner.plan(
            snapshot("hive" to localHive, "feeding" to localFeeding),
            listOf(
                cloud("hive", 20L, remoteHive),
                cloud("feeding", 900L, remoteFeeding)
            )
        )

        val feedingItem = plan.items.single { it.entityType == "feeding" }
        assertEquals(LocalDataAttachmentDisposition.SKIP_DUPLICATE, feedingItem.disposition)
        assertEquals(900L, feedingItem.targetId)
        assertEquals(0, plan.newRecordCount)
    }

    @Test
    fun tombstonedCloudHiveIsNeverAutomaticallyResurrected() {
        val local = hive(20L, "H-1", "Yard")
        val tombstone = hive(20L, "H-1", "Yard").put("deleted", true)

        val plan = LocalDataAttachmentMergePlanner.plan(
            snapshot("hive" to local),
            listOf(cloud("hive", 20L, tombstone, deleted = true))
        )

        val item = plan.items.single()
        assertEquals(LocalDataAttachmentDisposition.REVIEW_REQUIRED, item.disposition)
        assertFalse(plan.isSafeToProceed)
    }

    @Test
    fun changedHistoryWithSameIdentityRequiresReviewInsteadOfOverwrite() {
        val localHive = hive(20L, "H-1", "Yard")
        val remoteHive = hive(20L, "H-1", "Yard")
        val localFeeding = feeding(30L, 20L, "feed-uuid-2", amount = 3.0)
        val remoteFeeding = feeding(900L, 20L, "feed-uuid-2", amount = 1.0)

        val plan = LocalDataAttachmentMergePlanner.plan(
            snapshot("hive" to localHive, "feeding" to localFeeding),
            listOf(
                cloud("hive", 20L, remoteHive),
                cloud("feeding", 900L, remoteFeeding)
            )
        )

        val feedingItem = plan.items.single { it.entityType == "feeding" }
        assertEquals(LocalDataAttachmentDisposition.REVIEW_REQUIRED, feedingItem.disposition)
        assertFalse(plan.isSafeToProceed)
    }

    @Test
    fun activeNfcTagAssignedToAnotherCloudHiveRequiresReview() {
        val localHive = hive(20L, "H-1", "Yard")
        val remoteHive = hive(99L, "H-99", "Yard")
        val localAssignment = JSONObject()
            .put("id", 40L)
            .put("tag_uid", "AABBCCDD")
            .put("hive_id", 20L)
            .put("assigned_at", 300L)
            .put("unassigned_at", JSONObject.NULL)
            .put("updated_at", 300L)
        val cloudAssignment = JSONObject(localAssignment.toString())
            .put("id", 41L)
            .put("hive_id", 99L)

        val plan = LocalDataAttachmentMergePlanner.plan(
            snapshot("hive" to localHive, "nfc_assignment" to localAssignment),
            listOf(
                cloud("hive", 99L, remoteHive),
                cloud("nfc_assignment", 41L, cloudAssignment)
            )
        )

        val item = plan.items.single { it.entityType == "nfc_assignment" }
        assertEquals(LocalDataAttachmentDisposition.REVIEW_REQUIRED, item.disposition)
    }

    @Test
    fun taskRowsArePreservedAsLocalOnlyRatherThanAssumedCloudSynced() {
        val task = JSONObject()
            .put("id", 55L)
            .put("hive_id", 20L)
            .put("title", "Check feed")
            .put("due_at", 500L)
            .put("completed", false)
        val plan = LocalDataAttachmentMergePlanner.plan(snapshot(tasks = JSONArray().put(task)), emptyList())

        val item = plan.items.single()
        assertEquals("task", item.entityType)
        assertEquals(LocalDataAttachmentDisposition.PRESERVE_LOCAL_ONLY, item.disposition)
        assertEquals(1, plan.taskCount)
    }

    @Test
    fun mergePlanIsDeterministicAcrossRepeatedPreviews() {
        val local = apiary(10L, "North Yard", "local")
        val remote = apiary(10L, "South Yard", "remote")
        val snapshot = snapshot("apiary" to local)
        val cloud = listOf(cloud("apiary", 10L, remote))

        val first = LocalDataAttachmentMergePlanner.plan(snapshot, cloud).items.single()
        val second = LocalDataAttachmentMergePlanner.plan(snapshot, cloud).items.single()

        assertEquals(first.targetId, second.targetId)
        assertEquals(first.disposition, second.disposition)
        assertTrue(first.targetId > 0L)
    }

    @Test
    fun sameTimestampInspectionWithDifferentContentRequiresReview() {
        val localHive = hive(20L, "H-1", "Yard")
        val remoteHive = hive(20L, "H-1", "Yard")
        val localInspection = JSONObject()
            .put("id", 30L)
            .put("hive_id", 20L)
            .put("created_at", 250L)
            .put("updated_at", 250L)
            .put("strength", 5)
            .put("queen_status", "Laying")
            .put("mite_count", 1)
            .put("sample_size", 300)
            .put("notes", "Local inspection")
        val remoteInspection = JSONObject(localInspection.toString())
            .put("id", 900L)
            .put("notes", "Different cloud inspection")

        val plan = LocalDataAttachmentMergePlanner.plan(
            snapshot("hive" to localHive, "inspection" to localInspection),
            listOf(
                cloud("hive", 20L, remoteHive),
                cloud("inspection", 900L, remoteInspection)
            )
        )

        val item = plan.items.single { it.entityType == "inspection" }
        assertEquals(LocalDataAttachmentDisposition.REVIEW_REQUIRED, item.disposition)
        assertFalse(plan.isSafeToProceed)
    }

    @Test
    fun multipleCloudRowsWithOneStableUuidRequireReview() {
        val localHive = hive(20L, "H-1", "Yard")
        val remoteHive = hive(20L, "H-1", "Yard")
        val localFeeding = feeding(30L, 20L, "feed-uuid-duplicate")
        val remoteOne = feeding(900L, 20L, "feed-uuid-duplicate")
        val remoteTwo = feeding(901L, 20L, "feed-uuid-duplicate")

        val plan = LocalDataAttachmentMergePlanner.plan(
            snapshot("hive" to localHive, "feeding" to localFeeding),
            listOf(
                cloud("hive", 20L, remoteHive),
                cloud("feeding", 900L, remoteOne),
                cloud("feeding", 901L, remoteTwo)
            )
        )

        val item = plan.items.single { it.entityType == "feeding" }
        assertEquals(LocalDataAttachmentDisposition.REVIEW_REQUIRED, item.disposition)
    }

    @Test
    fun locallyDeletedApiaryHiveAndHistoryRemainLocalOnly() {
        val localApiary = apiary(10L, "Closed Yard").put("deleted", true)
        val localHive = hive(20L, "H-1", "Closed Yard", 10L)
        val localFeeding = feeding(30L, 20L, "feed-from-deleted-hive")

        val plan = LocalDataAttachmentMergePlanner.plan(
            snapshot(
                "apiary" to localApiary,
                "hive" to localHive,
                "feeding" to localFeeding
            ),
            emptyList()
        )

        assertEquals(
            LocalDataAttachmentDisposition.PRESERVE_LOCAL_ONLY,
            plan.items.single { it.entityType == "apiary" }.disposition
        )
        assertEquals(
            LocalDataAttachmentDisposition.PRESERVE_LOCAL_ONLY,
            plan.items.single { it.entityType == "hive" }.disposition
        )
        assertEquals(
            LocalDataAttachmentDisposition.PRESERVE_LOCAL_ONLY,
            plan.items.single { it.entityType == "feeding" }.disposition
        )
        assertEquals(3, plan.localOnlyCount)
    }

    @Test
    fun pendingDeleteOutboxEntriesAreReportedButNotReplayedIntoTargetAccount() {
        val base = JSONObject(snapshot("apiary" to apiary(10L, "Yard")))
        val outbox = JSONArray()
            .put(
                JSONObject()
                    .put("entity_type", "hive")
                    .put("entity_id", 20L)
                    .put("operation", "delete")
                    .put("state", "pending")
            )
            .put(
                JSONObject()
                    .put("entity_type", "feeding")
                    .put("entity_id", 30L)
                    .put("operation", "upsert")
                    .put("state", "pending")
            )
        base.put("syncOutbox", outbox)
        base.put(
            "actionBatchOutbox",
            JSONArray().put(JSONObject().put("state", "pending"))
        )

        val plan = LocalDataAttachmentMergePlanner.plan(base.toString(), emptyList())

        assertEquals(2, plan.pendingSyncWriteCount)
        assertEquals(1, plan.pendingDeleteCount)
        assertEquals(1, plan.pendingActionBatchCount)
        assertEquals(0, plan.pendingWorkspaceActionCount)
    }

    @Test
    fun legacyHiveWithoutApiaryIdInheritsTheMappedLocalApiaryId() {
        val localApiary = apiary(10L, "North Yard")
        val remoteApiary = apiary(999L, "North Yard")
        val legacyHive = hive(20L, "H-1", "North Yard", apiaryId = null)

        val plan = LocalDataAttachmentMergePlanner.plan(
            snapshot("apiary" to localApiary, "hive" to legacyHive),
            listOf(cloud("apiary", 999L, remoteApiary))
        )

        val hiveItem = plan.items.single { it.entityType == "hive" }
        assertEquals(999L, hiveItem.targetApiaryId)
    }

    @Test
    fun stableHistoryUuidCollisionAcrossDifferentHivesRequiresReview() {
        val localHive = hive(20L, "H-1", "Yard")
        val otherCloudHive = hive(99L, "H-99", "Other Yard")
        val localFeeding = feeding(30L, 20L, "globally-unique-feed-uuid")
        val otherHiveFeeding = feeding(900L, 99L, "globally-unique-feed-uuid")

        val plan = LocalDataAttachmentMergePlanner.plan(
            snapshot("hive" to localHive, "feeding" to localFeeding),
            listOf(
                cloud("hive", 99L, otherCloudHive),
                cloud("feeding", 900L, otherHiveFeeding)
            )
        )

        val item = plan.items.single { it.entityType == "feeding" }
        assertEquals(LocalDataAttachmentDisposition.REVIEW_REQUIRED, item.disposition)
    }

    @Test
    fun invalidSnapshotFormatIsRejected() {
        try {
            LocalDataAttachmentMergePlanner.plan("""{"format":"other"}""", emptyList())
            throw AssertionError("Expected snapshot format validation to fail.")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("not a BeeKeep attachment snapshot"))
        }
    }
}
