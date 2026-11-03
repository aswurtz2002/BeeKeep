package com.beekeep.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPayloadAllowListTest {
    @Test
    fun feedAllowsOnlyFeedingFields() {
        assertTrue(ActionPayloadAllowList.accepts("feed", setOf("feed_type", "ratio", "amount", "unit", "notes")))
        assertFalse(ActionPayloadAllowList.accepts("feed", setOf("feed_type", "amount", "queen_status")))
        assertFalse(ActionPayloadAllowList.accepts("feed", setOf("feed_type", "amount", "tag_uid")))
    }

    @Test
    fun treatmentAllowsOnlyTreatmentFields() {
        assertTrue(ActionPayloadAllowList.accepts("treat", setOf("treatment_type", "product", "inserted_at", "removal_at", "withdrawal_until", "notes")))
        assertFalse(ActionPayloadAllowList.accepts("treat", setOf("product", "queen_status")))
        assertFalse(ActionPayloadAllowList.accepts("treat", setOf("product", "feed_type")))
    }

    @Test
    fun harvestAllowsOnlyHarvestFields() {
        assertTrue(ActionPayloadAllowList.accepts("harvest", setOf("supers_pulled", "wet_honey_weight", "dry_honey_weight", "weight_unit", "wax_weight", "propolis_weight", "notes")))
        assertFalse(ActionPayloadAllowList.accepts("harvest", setOf("dry_honey_weight", "tag_uid")))
        assertFalse(ActionPayloadAllowList.accepts("harvest", setOf("dry_honey_weight", "queen_status")))
    }

    @Test
    fun unknownActionAndEmptyFieldsAreNotAccepted() {
        assertFalse(ActionPayloadAllowList.accepts("inspection", setOf("notes")))
        assertFalse(ActionPayloadAllowList.accepts("feed", emptySet()))
    }
}
