package com.beekeep.app.cloud

import com.beekeep.app.data.LocalDataAttachmentSnapshotInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Durable, app-private checkpoint for a single account attachment.
 *
 * The plan and original cloud inventory fingerprint are frozen before the first insert. On
 * resume, the gateway can distinguish this attachment's already-inserted rows from unrelated
 * cloud changes. A checksum detects partial writes or tampering; the snapshot remains the
 * authoritative recovery copy.
 */
internal data class LocalDataAttachmentExecutionJournal(
    val accountId: String,
    val snapshotFileName: String,
    val snapshotSha256: String,
    val baseCloudInventorySha256: String,
    val plan: LocalDataAttachmentMergePlan,
    val createdAt: Long,
    val localDatabaseApplied: Boolean = false
)

internal object LocalDataAttachmentExecutionStore {
    private const val DIRECTORY_NAME = "local-data-attachment-executions"
    private const val FORMAT = "beekeep-local-attachment-execution"
    private const val VERSION = 1

    fun save(filesDirectory: File, journal: LocalDataAttachmentExecutionJournal) {
        validateJournal(journal)
        val directory = File(filesDirectory, DIRECTORY_NAME)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("BeeKeep could not create the private attachment checkpoint folder.")
        }
        require(directory.isDirectory) { "The attachment checkpoint path is not a directory." }

        val destination = File(directory, fileName(journal.snapshotSha256)).canonicalFile
        require(destination.parentFile == directory.canonicalFile) {
            "The attachment checkpoint path is invalid."
        }
        val payload = encodeJournal(journal)
        val payloadText = payload.toString()
        val envelope = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("payload", payload)
            .put("sha256", sha256(payloadText.toByteArray(StandardCharsets.UTF_8)))
        val bytes = envelope.toString().toByteArray(StandardCharsets.UTF_8)
        val temporary = File.createTempFile("attachment-checkpoint-", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(destination)) {
                throw IllegalStateException("BeeKeep could not save the attachment checkpoint.")
            }
            val saved = destination.readBytes()
            require(saved.contentEquals(bytes)) { "The attachment checkpoint failed its read-back check." }
            decodeJournal(String(saved, StandardCharsets.UTF_8), journal.accountId, journal.snapshotFileName, journal.snapshotSha256)
        } finally {
            temporary.takeIf { it.exists() }?.delete()
        }
    }

    fun read(
        filesDirectory: File,
        snapshotInfo: LocalDataAttachmentSnapshotInfo,
        accountId: String
    ): LocalDataAttachmentExecutionJournal? {
        val directory = File(filesDirectory, DIRECTORY_NAME).canonicalFile
        if (!directory.isDirectory) return null
        val file = File(directory, fileName(snapshotInfo.sha256)).canonicalFile
        require(file.parentFile == directory && file.name == fileName(snapshotInfo.sha256)) {
            "The attachment checkpoint path is invalid."
        }
        if (!file.isFile) return null
        return decodeJournal(
            String(file.readBytes(), StandardCharsets.UTF_8),
            accountId,
            snapshotInfo.fileName,
            snapshotInfo.sha256
        )
    }

    private fun encodeJournal(journal: LocalDataAttachmentExecutionJournal): JSONObject {
        val items = JSONArray().apply {
            journal.plan.items.forEach { item ->
                put(
                    JSONObject()
                        .put("entityType", item.entityType)
                        .put("sourceId", item.sourceId)
                        .put("targetId", item.targetId)
                        .put("disposition", item.disposition.name)
                        .put("reason", item.reason)
                        .put("targetHiveId", item.targetHiveId ?: JSONObject.NULL)
                        .put("targetApiaryId", item.targetApiaryId ?: JSONObject.NULL)
                )
            }
        }
        return JSONObject()
            .put("accountId", journal.accountId)
            .put("snapshotFileName", journal.snapshotFileName)
            .put("snapshotSha256", journal.snapshotSha256)
            .put("baseCloudInventorySha256", journal.baseCloudInventorySha256)
            .put("createdAt", journal.createdAt)
            .put("localDatabaseApplied", journal.localDatabaseApplied)
            .put("plan", JSONObject()
                .put("accountId", journal.plan.accountId)
                .put("items", items)
                .put("taskCount", journal.plan.taskCount)
                .put("noteDraftCount", journal.plan.noteDraftCount)
                .put("formDraftCount", journal.plan.formDraftCount)
                .put("pendingSyncWriteCount", journal.plan.pendingSyncWriteCount)
                .put("pendingDeleteCount", journal.plan.pendingDeleteCount)
                .put("pendingActionBatchCount", journal.plan.pendingActionBatchCount)
                .put("pendingWorkspaceActionCount", journal.plan.pendingWorkspaceActionCount)
                .put("cloudInventorySha256", journal.plan.cloudInventorySha256))
    }

    private fun decodeJournal(
        serialized: String,
        expectedAccountId: String,
        expectedSnapshotFileName: String,
        expectedSnapshotSha256: String
    ): LocalDataAttachmentExecutionJournal {
        val envelope = JSONObject(serialized)
        require(envelope.optString("format") == FORMAT && envelope.optInt("version", -1) == VERSION) {
            "The attachment checkpoint format is not supported."
        }
        val payload = envelope.optJSONObject("payload")
            ?: throw IllegalArgumentException("The attachment checkpoint is incomplete.")
        val payloadText = payload.toString()
        require(envelope.optString("sha256") == sha256(payloadText.toByteArray(StandardCharsets.UTF_8))) {
            "The attachment checkpoint failed its integrity check."
        }
        val planJson = payload.getJSONObject("plan")
        val itemArray = planJson.getJSONArray("items")
        val items = (0 until itemArray.length()).map { index ->
            val item = itemArray.getJSONObject(index)
            LocalDataAttachmentMergeItem(
                entityType = item.getString("entityType"),
                sourceId = item.getLong("sourceId"),
                targetId = item.getLong("targetId"),
                disposition = LocalDataAttachmentDisposition.valueOf(item.getString("disposition")),
                reason = item.optString("reason"),
                targetHiveId = item.optLong("targetHiveId", -1L).takeIf { it > 0L },
                targetApiaryId = item.optLong("targetApiaryId", -1L).takeIf { it > 0L }
            )
        }
        val plan = LocalDataAttachmentMergePlan(
            accountId = planJson.getString("accountId"),
            items = items,
            taskCount = planJson.getInt("taskCount"),
            noteDraftCount = planJson.getInt("noteDraftCount"),
            formDraftCount = planJson.getInt("formDraftCount"),
            pendingSyncWriteCount = planJson.getInt("pendingSyncWriteCount"),
            pendingDeleteCount = planJson.getInt("pendingDeleteCount"),
            pendingActionBatchCount = planJson.getInt("pendingActionBatchCount"),
            pendingWorkspaceActionCount = planJson.getInt("pendingWorkspaceActionCount"),
            cloudInventorySha256 = planJson.optString(
                "cloudInventorySha256",
                payload.optString("baseCloudInventorySha256")
            )
        )
        val journal = LocalDataAttachmentExecutionJournal(
            accountId = payload.getString("accountId"),
            snapshotFileName = payload.getString("snapshotFileName"),
            snapshotSha256 = payload.getString("snapshotSha256"),
            baseCloudInventorySha256 = payload.getString("baseCloudInventorySha256"),
            plan = plan,
            createdAt = payload.getLong("createdAt"),
            localDatabaseApplied = payload.optBoolean("localDatabaseApplied", false)
        )
        validateJournal(journal)
        require(journal.accountId == expectedAccountId && journal.plan.accountId == expectedAccountId) {
            "This attachment checkpoint belongs to a different signed-in account."
        }
        require(journal.snapshotFileName == expectedSnapshotFileName &&
            journal.snapshotSha256 == expectedSnapshotSha256
        ) {
            "The attachment checkpoint does not match the selected recovery snapshot."
        }
        return journal
    }

    private fun validateJournal(journal: LocalDataAttachmentExecutionJournal) {
        require(journal.accountId.isNotBlank())
        require(journal.plan.accountId == journal.accountId)
        require(journal.snapshotFileName.matches(Regex("^attachment-[0-9]+-[a-f0-9-]+\\.json$")))
        require(journal.snapshotSha256.matches(Regex("^[a-f0-9]{64}$")))
        require(journal.baseCloudInventorySha256.matches(Regex("^[a-f0-9]{64}$")))
        require(journal.plan.cloudInventorySha256 == journal.baseCloudInventorySha256) {
            "The saved merge plan does not match its cloud inventory checkpoint."
        }
        require(journal.plan.isSafeToProceed) {
            "The attachment plan contains records that still need manual review."
        }
        require(journal.createdAt > 0L)
    }

    private fun fileName(snapshotSha256: String): String {
        require(snapshotSha256.matches(Regex("^[a-f0-9]{64}$"))) { "The recovery snapshot checksum is invalid." }
        val key = sha256(snapshotSha256.toByteArray(StandardCharsets.UTF_8))
        return "execution-$key.json"
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
