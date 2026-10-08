package com.beekeep.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcAssignmentResolverTest {

    private fun assignment(id: Long, tag: String, hive: Long, at: Long, unassignedAt: Long? = null) =
        NfcTagAssignmentEntity(id, tag, hive, at, unassignedAt)

    @Test
    fun `active incoming with no conflicts stays active and closes nothing`() {
        val incoming = assignment(10, "A123", 83, at = 100)
        val result = NfcAssignmentResolver.merge(null, null, incoming)
        assertTrue(result.incoming.isActive)
        assertTrue(result.toClose.isEmpty())
    }

    @Test
    fun `newer incoming wins the tag and closes the local owner`() {
        val local = assignment(5, "A123", 47, at = 50)
        val incoming = assignment(10, "A123", 83, at = 100)
        val result = NfcAssignmentResolver.merge(local, null, incoming)
        assertTrue(result.incoming.isActive)
        assertEquals(listOf(local.copy(unassignedAt = 100)), result.toClose)
    }

    @Test
    fun `older incoming loses the tag and arrives closed`() {
        val local = assignment(5, "A123", 47, at = 100)
        val incoming = assignment(10, "A123", 83, at = 50)
        val result = NfcAssignmentResolver.merge(local, null, incoming)
        assertEquals(100L, result.incoming.unassignedAt)
        assertTrue(result.toClose.isEmpty())
    }

    @Test
    fun `ties resolve deterministically by higher id`() {
        val local = assignment(9, "A123", 47, at = 100)
        val incoming = assignment(10, "A123", 83, at = 100)
        val result = NfcAssignmentResolver.merge(local, null, incoming)
        assertTrue(result.incoming.isActive)
        assertEquals(listOf(local.copy(unassignedAt = 100)), result.toClose)

        val reversed = NfcAssignmentResolver.merge(incoming, null, local)
        assertEquals(100L, reversed.incoming.unassignedAt)
        assertTrue(reversed.toClose.isEmpty())
    }

    @Test
    fun `closed incoming applies without displacing a local active assignment`() {
        val local = assignment(5, "A123", 47, at = 50)
        val incoming = assignment(10, "A123", 83, at = 60, unassignedAt = 70)
        val result = NfcAssignmentResolver.merge(local, null, incoming)
        assertEquals(70L, result.incoming.unassignedAt)
        assertTrue(result.toClose.isEmpty())
    }

    @Test
    fun `same id conflict is ignored so replace stays idempotent`() {
        val local = assignment(10, "A123", 47, at = 50)
        val incoming = assignment(10, "A123", 47, at = 50)
        val result = NfcAssignmentResolver.merge(local, local, incoming)
        assertTrue(result.incoming.isActive)
        assertTrue(result.toClose.isEmpty())
    }

    @Test
    fun `active incoming replaces the hive's existing different tag`() {
        val localForHive = assignment(5, "OLD9", 83, at = 40)
        val incoming = assignment(10, "A123", 83, at = 100)
        val result = NfcAssignmentResolver.merge(null, localForHive, incoming)
        assertTrue(result.incoming.isActive)
        assertEquals(listOf(localForHive.copy(unassignedAt = 100)), result.toClose)
    }

    @Test
    fun `already closed local assignments do not close the incoming one`() {
        val closedLocal = assignment(5, "A123", 47, at = 10, unassignedAt = 20)
        val incoming = assignment(10, "A123", 83, at = 30)
        val result = NfcAssignmentResolver.merge(closedLocal, null, incoming)
        assertTrue(result.incoming.isActive)
        assertTrue(result.toClose.isEmpty())
    }
}
