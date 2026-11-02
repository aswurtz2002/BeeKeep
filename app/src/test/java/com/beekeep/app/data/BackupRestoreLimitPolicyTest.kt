package com.beekeep.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreLimitPolicyTest {
    @Test
    fun restoringTheSameLogicalRecordsDoesNotConsumeAdditionalQuota() {
        val assessment = assessBackupRestoreQuotas(
            currentApiaryNames = listOf("North Yard"),
            importedApiaryNames = listOf("north yard"),
            currentHiveKeys = listOf("1" to "North Yard", "2" to "North Yard"),
            importedHiveKeys = listOf("1" to "north yard", "2" to "NORTH YARD"),
            hiveLimit = 15,
            apiaryLimit = 3,
            planName = "Hobbyist"
        )

        assertEquals(2, assessment.resultingHiveCount)
        assertEquals(0, assessment.newHiveCount)
        assertEquals(1, assessment.resultingApiaryCount)
        assertEquals(0, assessment.newApiaryCount)
    }

    @Test
    fun existingAccountAlreadyOverLimitCanRestoreHistoryForExistingHives() {
        val current = (1..16).map { it.toString() to "North Yard" }
        val assessment = assessBackupRestoreQuotas(
            currentApiaryNames = listOf("North Yard"),
            importedApiaryNames = listOf("North Yard"),
            currentHiveKeys = current,
            importedHiveKeys = current.take(2),
            hiveLimit = 15,
            apiaryLimit = 3,
            planName = "Hobbyist"
        )

        assertEquals(16, assessment.resultingHiveCount)
        assertEquals(0, assessment.newHiveCount)
    }

    @Test
    fun addingHiveBeyondPlanLimitIsRejectedBeforeRestore() {
        val current = (1..15).map { it.toString() to "North Yard" }
        val error = runCatching {
            assessBackupRestoreQuotas(
                currentApiaryNames = listOf("North Yard"),
                importedApiaryNames = listOf("North Yard"),
                currentHiveKeys = current,
                importedHiveKeys = listOf("16" to "North Yard"),
                hiveLimit = 15,
                apiaryLimit = 3,
                planName = "Hobbyist"
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("No records were restored"))
        assertTrue(error?.message.orEmpty().contains("15 hives"))
    }

    @Test
    fun addingApiaryBeyondPlanLimitIsRejectedBeforeRestore() {
        val error = runCatching {
            assessBackupRestoreQuotas(
                currentApiaryNames = listOf("North", "South", "East"),
                importedApiaryNames = listOf("West"),
                currentHiveKeys = emptyList(),
                importedHiveKeys = emptyList(),
                hiveLimit = 15,
                apiaryLimit = 3,
                planName = "Hobbyist"
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("No records were restored"))
        assertTrue(error?.message.orEmpty().contains("3 apiaries"))
    }

    @Test
    fun backupMayAddRecordsWhenTheMergedResultFitsBothLimits() {
        val assessment = assessBackupRestoreQuotas(
            currentApiaryNames = listOf("North"),
            importedApiaryNames = listOf("South"),
            currentHiveKeys = listOf("1" to "North"),
            importedHiveKeys = listOf("2" to "South"),
            hiveLimit = 15,
            apiaryLimit = 3,
            planName = "Hobbyist"
        )

        assertEquals(2, assessment.resultingHiveCount)
        assertEquals(1, assessment.newHiveCount)
        assertEquals(2, assessment.resultingApiaryCount)
        assertEquals(1, assessment.newApiaryCount)
    }
    @Test
    fun personalBackupRestoreIsAvailableWithoutWorkspaceMembership() {
        assertTrue(canRestoreBackupToWorkspace(isPersonalWorkspace = true, memberRole = null))
    }

    @Test
    fun teamBackupRestoreUsesOnlyTheWorkspaceOwnersEntitlement() {
        assertTrue(canRestoreBackupToWorkspace(isPersonalWorkspace = false, memberRole = "owner"))
        assertFalse(canRestoreBackupToWorkspace(isPersonalWorkspace = false, memberRole = "admin"))
        assertFalse(canRestoreBackupToWorkspace(isPersonalWorkspace = false, memberRole = "member"))
        assertFalse(canRestoreBackupToWorkspace(isPersonalWorkspace = false, memberRole = "viewer"))
        assertFalse(canRestoreBackupToWorkspace(isPersonalWorkspace = false, memberRole = null))
    }

}
