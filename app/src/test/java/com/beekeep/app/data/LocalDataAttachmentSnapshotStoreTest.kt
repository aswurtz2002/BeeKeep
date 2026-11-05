package com.beekeep.app.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalDataAttachmentSnapshotStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun saveWritesAnImmutableSnapshotThatCanBeReadAfterVerification() {
        val original = """{"format":"beekeep-local-attachment-snapshot","schemaVersion":1}"""
        val info = LocalDataAttachmentSnapshotStore.save(
            filesDirectory = temporaryFolder.root,
            createdAt = 1_800_000_000_000L,
            snapshotJson = original,
            recordCounts = mapOf("hive" to 4, "inspection" to 9),
            pendingSyncWrites = 2,
            pendingActionBatches = 1,
            pendingWorkspaceActions = 3,
            noteDrafts = 2,
            formDrafts = 1
        )

        assertEquals(original, LocalDataAttachmentSnapshotStore.readVerified(temporaryFolder.root, info))
        assertEquals(13, info.totalRecords)
        assertEquals(2, info.pendingSyncWrites)
        assertEquals(1, info.pendingActionBatches)
        assertEquals(3, info.pendingWorkspaceActions)
        assertEquals(2, info.noteDrafts)
        assertEquals(1, info.formDrafts)
        assertEquals(original.toByteArray(Charsets.UTF_8).size.toLong(), info.sizeBytes)
    }

    @Test
    fun eachPreviewCreatesADistinctSnapshotInsteadOfOverwritingThePreviousCopy() {
        val first = LocalDataAttachmentSnapshotStore.save(
            temporaryFolder.root, 42L, """{"copy":1}""", emptyMap(), 0, 0, 0, 0, 0
        )
        val second = LocalDataAttachmentSnapshotStore.save(
            temporaryFolder.root, 42L, """{"copy":2}""", emptyMap(), 0, 0, 0, 0, 0
        )

        assertNotEquals(first.fileName, second.fileName)
        assertEquals("""{"copy":1}""", LocalDataAttachmentSnapshotStore.readVerified(temporaryFolder.root, first))
        assertEquals("""{"copy":2}""", LocalDataAttachmentSnapshotStore.readVerified(temporaryFolder.root, second))
    }

    @Test
    fun readRejectsSnapshotWhenItsContentsHaveChanged() {
        val info = LocalDataAttachmentSnapshotStore.save(
            temporaryFolder.root, 77L, """{"safe":true}""", emptyMap(), 0, 0, 0, 0, 0
        )
        val snapshot = File(
            File(temporaryFolder.root, "local-data-attachment-snapshots"),
            info.fileName
        )
        snapshot.writeText("""{"safe":false}""")

        assertThrows(IllegalArgumentException::class.java) {
            LocalDataAttachmentSnapshotStore.readVerified(temporaryFolder.root, info)
        }
    }

    @Test
    fun readRejectsPathTraversalInSnapshotFileName() {
        val info = LocalDataAttachmentSnapshotInfo(
            fileName = "../outside.json",
            createdAt = 1L,
            sizeBytes = 0L,
            sha256 = "",
            recordCounts = emptyMap(),
            pendingSyncWrites = 0,
            pendingActionBatches = 0,
            pendingWorkspaceActions = 0,
            noteDrafts = 0,
            formDrafts = 0
        )

        assertThrows(IllegalArgumentException::class.java) {
            LocalDataAttachmentSnapshotStore.readVerified(temporaryFolder.root, info)
        }
    }
}
