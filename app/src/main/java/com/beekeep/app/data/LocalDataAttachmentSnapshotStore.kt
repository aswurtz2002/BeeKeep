package com.beekeep.app.data

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * Immutable app-private recovery snapshots used by the account attachment review.
 *
 * A snapshot is first written to a temporary file in the destination directory, flushed to
 * storage, and then renamed into place. The SHA-256 is checked against the saved bytes before
 * the snapshot is reported as usable. Snapshot files are never uploaded by this class.
 */
internal data class LocalDataAttachmentSnapshotInfo(
    val fileName: String,
    val createdAt: Long,
    val sizeBytes: Long,
    val sha256: String,
    val recordCounts: Map<String, Int>,
    val pendingSyncWrites: Int,
    val pendingActionBatches: Int,
    val pendingWorkspaceActions: Int,
    val noteDrafts: Int,
    val formDrafts: Int
) {
    val totalRecords: Int get() = recordCounts.values.sum()
}

internal object LocalDataAttachmentSnapshotStore {
    private const val DIRECTORY_NAME = "local-data-attachment-snapshots"

    fun save(
        filesDirectory: File,
        createdAt: Long,
        snapshotJson: String,
        recordCounts: Map<String, Int>,
        pendingSyncWrites: Int,
        pendingActionBatches: Int,
        pendingWorkspaceActions: Int,
        noteDrafts: Int,
        formDrafts: Int
    ): LocalDataAttachmentSnapshotInfo {
        require(snapshotJson.isNotBlank()) { "The local recovery snapshot is empty." }
        val directory = File(filesDirectory, DIRECTORY_NAME)
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("BeeKeep could not create its private recovery-snapshot folder.")
        }
        require(directory.isDirectory) { "The local recovery-snapshot path is not a directory." }

        val fileName = "attachment-$createdAt-${UUID.randomUUID()}.json"
        val destination = File(directory, fileName)
        val temporary = File.createTempFile("attachment-", ".tmp", directory)
        try {
            val bytes = snapshotJson.toByteArray(StandardCharsets.UTF_8)
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(destination)) {
                throw IllegalStateException("BeeKeep could not finalize the local recovery snapshot.")
            }

            val savedBytes = destination.readBytes()
            val checksum = sha256(savedBytes)
            require(savedBytes.contentEquals(bytes) && checksum == sha256(bytes)) {
                "The local recovery snapshot could not be verified after saving."
            }

            val info = LocalDataAttachmentSnapshotInfo(
                fileName = fileName,
                createdAt = createdAt,
                sizeBytes = savedBytes.size.toLong(),
                sha256 = checksum,
                recordCounts = recordCounts.toMap(),
                pendingSyncWrites = pendingSyncWrites,
                pendingActionBatches = pendingActionBatches,
                pendingWorkspaceActions = pendingWorkspaceActions,
                noteDrafts = noteDrafts,
                formDrafts = formDrafts
            )
            writeManifest(directory, info)
            return info
        } catch (error: Throwable) {
            destination.takeIf { it.exists() }?.delete()
            File(directory, "$fileName.manifest").takeIf { it.exists() }?.delete()
            throw error
        } finally {
            temporary.takeIf { it.exists() }?.delete()
        }
    }

    /** Lists recoverable snapshots after an app restart and verifies each file against its manifest. */
    fun listVerifiedSnapshots(filesDirectory: File): List<LocalDataAttachmentSnapshotInfo> {
        val directory = File(filesDirectory, DIRECTORY_NAME).canonicalFile
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.matches(Regex("^attachment-[0-9]+-[a-f0-9-]+\\.json$")) }
            .mapNotNull { snapshotFile ->
                runCatching {
                    val manifest = File(directory, "${snapshotFile.name}.manifest")
                    val lines = manifest.readLines(StandardCharsets.UTF_8)
                    require(lines.size >= 9 && lines[0] == "1") { "Snapshot manifest is invalid." }
                    val createdAt = lines[1].toLong()
                    val sizeBytes = lines[2].toLong()
                    val checksum = lines[3]
                    val counts = lines.drop(9).associate { line ->
                        val separator = line.indexOf('=')
                        require(separator > 0) { "Snapshot manifest count is invalid." }
                        line.substring(0, separator) to line.substring(separator + 1).toInt()
                    }
                    val bytes = snapshotFile.readBytes()
                    require(bytes.size.toLong() == sizeBytes && sha256(bytes) == checksum) {
                        "Snapshot file failed integrity verification."
                    }
                    LocalDataAttachmentSnapshotInfo(
                        fileName = snapshotFile.name,
                        createdAt = createdAt,
                        sizeBytes = sizeBytes,
                        sha256 = checksum,
                        recordCounts = counts,
                        pendingSyncWrites = lines[4].toInt(),
                        pendingActionBatches = lines[5].toInt(),
                        pendingWorkspaceActions = lines[6].toInt(),
                        noteDrafts = lines[7].toInt(),
                        formDrafts = lines[8].toInt()
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.createdAt }
            .toList()
    }

    private fun writeManifest(directory: File, info: LocalDataAttachmentSnapshotInfo) {
        val manifest = File(directory, "${info.fileName}.manifest")
        val temporary = File.createTempFile("attachment-meta-", ".tmp", directory)
        try {
            val lines = buildList {
                add("1")
                add(info.createdAt.toString())
                add(info.sizeBytes.toString())
                add(info.sha256)
                add(info.pendingSyncWrites.toString())
                add(info.pendingActionBatches.toString())
                add(info.pendingWorkspaceActions.toString())
                add(info.noteDrafts.toString())
                add(info.formDrafts.toString())
                info.recordCounts.toSortedMap().forEach { (key, value) -> add("$key=$value") }
            }
            FileOutputStream(temporary).use { output ->
                output.write(lines.joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(manifest)) {
                throw IllegalStateException("BeeKeep could not finalize the recovery snapshot manifest.")
            }
        } finally {
            temporary.takeIf { it.exists() }?.delete()
        }
    }

    /** Loads a previously captured snapshot only after validating its name and checksum. */
    fun readVerified(filesDirectory: File, info: LocalDataAttachmentSnapshotInfo): String {
        require(info.fileName.matches(Regex("^attachment-[0-9]+-[a-f0-9-]+\\.json$"))) {
            "The recovery snapshot filename is invalid."
        }
        val directory = File(filesDirectory, DIRECTORY_NAME).canonicalFile
        val file = File(directory, info.fileName).canonicalFile
        require(file.parentFile == directory && file.isFile) {
            "The local recovery snapshot is no longer available on this device."
        }
        val bytes = file.readBytes()
        require(bytes.size.toLong() == info.sizeBytes && sha256(bytes) == info.sha256) {
            "The local recovery snapshot failed its integrity check."
        }
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
