package com.beekeep.app.cloud

import com.beekeep.app.data.LocalDataAttachmentSnapshotStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDataAttachmentExecutionStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun snapshotInfo() = LocalDataAttachmentSnapshotStore.save(
        filesDirectory = temporaryFolder.root,
        createdAt = 1_800_000_000_000L,
        snapshotJson = """{"format":"beekeep-local-attachment-snapshot","schemaVersion":1,"targetAccountId":"account-a","records":[]}""",
        recordCounts = emptyMap(),
        pendingSyncWrites = 0,
        pendingActionBatches = 0,
        pendingWorkspaceActions = 0,
        noteDrafts = 0,
        formDrafts = 0
    )

    private fun journal(snapshotFileName: String, snapshotSha256: String) =
        LocalDataAttachmentExecutionJournal(
            accountId = "account-a",
            snapshotFileName = snapshotFileName,
            snapshotSha256 = snapshotSha256,
            baseCloudInventorySha256 = "a".repeat(64),
            plan = LocalDataAttachmentMergePlan(
                accountId = "account-a",
                items = listOf(
                    LocalDataAttachmentMergeItem(
                        entityType = "apiary",
                        sourceId = 10L,
                        targetId = 10L,
                        disposition = LocalDataAttachmentDisposition.UPLOAD_NEW,
                        reason = "New local apiary"
                    )
                ),
                taskCount = 0,
                noteDraftCount = 0,
                formDraftCount = 0,
                pendingSyncWriteCount = 0,
                pendingDeleteCount = 0,
                pendingActionBatchCount = 0,
                pendingWorkspaceActionCount = 0,
                cloudInventorySha256 = "a".repeat(64)
            ),
            createdAt = 1_800_000_000_001L
        )

    @Test
    fun checkpointRoundTripsTheCompleteApprovedPlan() {
        val snapshot = snapshotInfo()
        val original = journal(snapshot.fileName, snapshot.sha256)

        LocalDataAttachmentExecutionStore.save(temporaryFolder.root, original)

        val restored = LocalDataAttachmentExecutionStore.read(
            temporaryFolder.root,
            snapshot,
            "account-a"
        )

        assertEquals(original, restored)
        assertEquals("a".repeat(64), restored?.plan?.cloudInventorySha256)
    }

    @Test
    fun checkpointRejectsPlanWithDifferentCloudInventoryFingerprint() {
        val snapshot = snapshotInfo()
        val valid = journal(snapshot.fileName, snapshot.sha256)
        val invalid = valid.copy(
            plan = valid.plan.copy(cloudInventorySha256 = "b".repeat(64))
        )

        assertThrows(IllegalArgumentException::class.java) {
            LocalDataAttachmentExecutionStore.save(temporaryFolder.root, invalid)
        }
    }

    @Test
    fun checkpointCannotBeReadForAnotherAccount() {
        val snapshot = snapshotInfo()
        LocalDataAttachmentExecutionStore.save(
            temporaryFolder.root,
            journal(snapshot.fileName, snapshot.sha256)
        )

        assertThrows(IllegalArgumentException::class.java) {
            LocalDataAttachmentExecutionStore.read(
                temporaryFolder.root,
                snapshot,
                "account-b"
            )
        }
    }

    @Test
    fun checkpointRejectsTamperedContents() {
        val snapshot = snapshotInfo()
        LocalDataAttachmentExecutionStore.save(
            temporaryFolder.root,
            journal(snapshot.fileName, snapshot.sha256)
        )
        val directory = File(temporaryFolder.root, "local-data-attachment-executions")
        directory.listFiles().orEmpty().single { it.extension == "json" }.writeText("{}")

        assertThrows(IllegalArgumentException::class.java) {
            LocalDataAttachmentExecutionStore.read(
                temporaryFolder.root,
                snapshot,
                "account-a"
            )
        }
    }

    @Test
    fun missingCheckpointIsNotTreatedAsAnExistingExecution() {
        val snapshot = snapshotInfo()

        assertNull(
            LocalDataAttachmentExecutionStore.read(
                temporaryFolder.root,
                snapshot,
                "account-a"
            )
        )
    }
}
