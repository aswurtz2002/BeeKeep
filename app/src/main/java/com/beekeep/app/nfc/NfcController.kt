package com.beekeep.app.nfc

import android.app.Activity
import android.content.Intent
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.NfcManager
import android.nfc.Tag
import android.nfc.TagLostException
import android.os.Bundle
import androidx.core.content.IntentCompat
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

data class NfcTagInfo(
    val uid: String,
    val technologies: List<String>,
    val ndefSupported: Boolean,
    val formatted: Boolean,
    val writable: Boolean?,
    val maxSizeBytes: Int?,
    val ndefType: String?,
    val storedPayload: String?,
    val hasBeeKeepLaunchRecord: Boolean = false
)

sealed interface NfcResult {
    data class Read(
        val info: NfcTagInfo,
        val text: String?,
        val launchPreparationError: String? = null
    ) : NfcResult {
        val uid: String get() = info.uid
    }
    data class Written(val info: NfcTagInfo) : NfcResult {
        val uid: String get() = info.uid
    }
    data class Error(val message: String) : NfcResult
}

class NfcController {
    private var adapter: NfcAdapter? = null
    @Volatile private var attachedActivity: Activity? = null
    private val active = AtomicBoolean(false)
    private val passiveReadInFlight = AtomicBoolean(false)
    private val passiveLock = Any()
    @Volatile private var activityResumed = false
    @Volatile private var passiveReaderActive = false
    @Volatile private var passiveReadListener: ((NfcResult.Read) -> Unit)? = null
    @Volatile private var knownHiveIdByTagUid: Map<String, Long> = emptyMap()
    @Volatile private var knownHiveTagsReady: Boolean = false
    private var lastHandledUid: String? = null
    private var lastHandledAt = 0L

    private companion object {
        const val PASSIVE_REPEAT_DELAY_MS = 1_800L
    }

    fun attach(activity: Activity) {
        attachedActivity = activity
        adapter = activity.getSystemService(NfcManager::class.java)?.defaultAdapter
    }

    /** Receives passive reads after the local UID-to-hive index is ready. */
    fun setPassiveReadListener(activity: Activity, listener: (NfcResult.Read) -> Unit) {
        passiveReadListener = listener
        if (activityResumed) enablePassiveIfNeeded(activity)
    }

    /** Prevents a cold-start NFC intent from immediately being delivered a second time. */
    fun rememberHandledUid(uid: String) {
        synchronized(passiveLock) {
            lastHandledUid = uid
            lastHandledAt = System.currentTimeMillis()
        }
    }

    fun resume(activity: Activity) {
        attach(activity)
        activityResumed = true
        enablePassiveIfNeeded(activity)
    }

    fun pause(activity: Activity) {
        activityResumed = false
        active.set(false)
        passiveReadInFlight.set(false)
        runCatching { adapter?.disableReaderMode(activity) }
        passiveReaderActive = false
    }

    fun isAvailable(): Boolean = adapter != null
    fun isEnabled(): Boolean = adapter?.isEnabled == true
    fun isScanning(): Boolean = active.get()

    /** Update the local UID-to-hive index before automatic tag writes are allowed. */
    fun updateKnownHiveTags(hiveIdByTagUid: Map<String, Long>, ready: Boolean) {
        knownHiveIdByTagUid = hiveIdByTagUid.entries
            .mapNotNull { (uid, hiveId) -> uid.trim().uppercase(java.util.Locale.ROOT).takeIf { it.isNotBlank() }?.let { it to hiveId } }
            .toMap()
        knownHiveTagsReady = ready
        if (ready) attachedActivity?.takeIf { activityResumed }?.let(::enablePassiveIfNeeded)
    }

    fun startRead(activity: Activity, onResult: (NfcResult) -> Unit) {
        start(activity, { tag -> read(tag) }, onResult)
    }

    /** Reads the tag, writing BeeKeep launch data immediately for blank/unassigned tags. */
    fun startReadAndPrepareForBeeKeep(activity: Activity, onResult: (NfcResult) -> Unit) {
        start(activity, { tag ->
            val result = read(tag)
            if (result is NfcResult.Read) prepareTagForBeeKeep(tag, result) else result
        }, onResult)
    }

    /**
     * Assign a hive and write its launch payload during the same NFC discovery.
     * This removes the old read-then-reprompt-then-write workflow for a new assignment.
     * The caller provides a UID conflict check so an existing tag is never overwritten
     * just because it has a blank or invalid payload.
     */
    fun startAssignAndWrite(
        activity: Activity,
        text: String,
        expectedUid: String? = null,
        isAssignedElsewhere: (String) -> Boolean,
        onResult: (NfcResult) -> Unit
    ) {
        start(activity, { tag ->
            val scannedUid = uid(tag)
            when {
                expectedUid != null && !scannedUid.equals(expectedUid, ignoreCase = true) ->
                    NfcResult.Error("This is tag $scannedUid, not the selected tag $expectedUid. No data was written.")
                isAssignedElsewhere(scannedUid) ->
                    // Return a read result so the UI can ask the user to confirm reassignment first.
                    read(tag)
                else -> write(tag, text, allowOverwriteOtherHive = false)
            }
        }, onResult)
    }

    fun startWrite(
        activity: Activity,
        text: String,
        onResult: (NfcResult) -> Unit,
        allowOverwriteOtherHive: Boolean = false,
        expectedUid: String? = null
    ) {
        start(activity, { tag ->
            val scannedUid = uid(tag)
            if (expectedUid != null && !scannedUid.equals(expectedUid, ignoreCase = true)) {
                NfcResult.Error("This is tag $scannedUid, not the assigned tag $expectedUid. Hold the same tag to write BeeKeep launch data.")
            } else {
                write(tag, text, allowOverwriteOtherHive)
            }
        }, onResult)
    }

    private fun start(
        activity: Activity,
        operation: (Tag) -> NfcResult,
        onResult: (NfcResult) -> Unit
    ) {
        val nfc = adapter ?: activity.getSystemService(NfcManager::class.java)?.defaultAdapter
        if (nfc == null) { onResult(NfcResult.Error("This phone does not have NFC.")); return }
        if (!nfc.isEnabled) { onResult(NfcResult.Error("NFC is turned off. Turn it on in Settings and try again.")); return }
        if (!active.compareAndSet(false, true)) {
            onResult(NfcResult.Error("NFC scan is already running."))
            return
        }
        // Explicit read/write operations temporarily take ownership from the
        // passive reader so the one-shot callback cannot compete with it.
        runCatching { nfc.disableReaderMode(activity) }
        passiveReaderActive = false
        val flags = readerFlags()
        val delivered = AtomicBoolean(false)
        try {
            nfc.enableReaderMode(activity, { tag ->
                if (!active.get()) return@enableReaderMode
                if (delivered.compareAndSet(false, true)) {
                    val result = runCatching { operation(tag) }
                        .getOrElse { e -> NfcResult.Error(friendlyError(e)) }
                    if (result is NfcResult.Read) rememberHandledUid(result.uid)
                    activity.runOnUiThread {
                        if (!active.get() || activity.isFinishing || activity.isDestroyed) {
                            stop(activity)
                        } else {
                            stop(activity)
                            onResult(result)
                        }
                    }
                }
            }, flags, Bundle())
        } catch (e: RuntimeException) {
            active.set(false)
            onResult(NfcResult.Error(friendlyError(e)))
            enablePassiveIfNeeded(activity)
        }
    }

    fun stop(activity: Activity) {
        runCatching { adapter?.disableReaderMode(activity) }
        passiveReaderActive = false
        active.set(false)
        passiveReadInFlight.set(false)
        enablePassiveIfNeeded(activity)
    }

    /**
     * Keep NDEF discovery enabled. This controller reads and writes NDEF records;
     * FLAG_READER_SKIP_NDEF_CHECK prevents Android from enumerating the Ndef
     * technology and disables NDEF-based app dispatch for the discovered tag.
     */
    private fun readerFlags(): Int =
        NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

    private fun enablePassiveIfNeeded(activity: Activity) {
        if (!activityResumed || active.get() || passiveReaderActive || !knownHiveTagsReady ||
            passiveReadListener == null || activity.isFinishing || activity.isDestroyed) return
        val nfc = adapter ?: activity.getSystemService(NfcManager::class.java)?.defaultAdapter ?: return
        if (!nfc.isEnabled) return

        try {
            nfc.enableReaderMode(activity, { tag ->
                if (!activityResumed || active.get() ||
                    !passiveReadInFlight.compareAndSet(false, true)) {
                    return@enableReaderMode
                }
                try {
                    val result = runCatching {
                        val readResult = read(tag)
                        if (readResult is NfcResult.Read) prepareTagForBeeKeep(tag, readResult) else readResult
                    }.getOrNull()
                    if (result is NfcResult.Read) {
                        val shouldDeliver = synchronized(passiveLock) {
                            val now = System.currentTimeMillis()
                            if (lastHandledUid == result.uid && now - lastHandledAt < PASSIVE_REPEAT_DELAY_MS) {
                                // Refresh while the same tag is still present. This prevents a
                                // held tag from repeatedly reopening the same hive.
                                lastHandledAt = now
                                false
                            } else {
                                lastHandledUid = result.uid
                                lastHandledAt = now
                                true
                            }
                        }
                        if (shouldDeliver) {
                            activity.runOnUiThread {
                                if (activityResumed && !active.get() &&
                                    !activity.isFinishing && !activity.isDestroyed) {
                                    passiveReadListener?.invoke(result)
                                }
                            }
                        }
                    }
                } finally {
                    passiveReadInFlight.set(false)
                }
            }, readerFlags(), Bundle())
            passiveReaderActive = true
        } catch (_: RuntimeException) {
            // Passive NFC is best-effort. The explicit Scan/Write actions will still
            // return a clear error if reader mode cannot be started.
            passiveReaderActive = false
        }
    }

    /** Reads an NDEF/tag-dispatch intent when BeeKeep is launched by an NFC tap.
     * Prefer the NDEF message supplied by Android in the intent so the app does not
     * need to reconnect to a tag that may already have moved away from the phone.
     */
    fun readIntent(intent: Intent?): NfcResult.Read? {
        if (intent == null) return null
        val tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Tag::class.java)
        val fallbackTag = tag ?: return null
        val rawMessages: List<NdefMessage>? = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)?.toList()
        } else {
            @Suppress("DEPRECATION")
            (intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES) as? Array<*>)
                ?.mapNotNull { it as? NdefMessage }
        }
        if (rawMessages != null) {
            val stored = rawMessages.asSequence()
                .flatMap { it.records.asSequence() }
                .mapNotNull(::parseRecord)
                .firstOrNull()
            val info = NfcTagInfo(
                uid = uid(fallbackTag),
                technologies = fallbackTag.techList.map { it.substringAfterLast('.') }.sorted(),
                ndefSupported = true,
                formatted = true,
                writable = null,
                maxSizeBytes = null,
                ndefType = null,
                storedPayload = stored,
                hasBeeKeepLaunchRecord = rawMessages.any { message -> message.records.any(::isBeeKeepAppRecord) }
            )
            return NfcResult.Read(info, stored)
        }

        // NDEF_DISCOVERED intents normally include their NDEF messages. If Android
        // delivers only EXTRA_TAG, do not reconnect during activity launch: the tag
        // may already be gone, and reconnecting here can stall the UI.
        val ndefSupported = Ndef.get(fallbackTag) != null
        val info = NfcTagInfo(
            uid = uid(fallbackTag),
            technologies = fallbackTag.techList.map { it.substringAfterLast('.') }.sorted(),
            ndefSupported = ndefSupported,
            formatted = false,
            writable = null,
            maxSizeBytes = null,
            ndefType = null,
            storedPayload = null
        )
        return NfcResult.Read(info, null)
    }

    private fun uid(tag: Tag): String = tag.id.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private fun read(tag: Tag): NfcResult {
        val id = uid(tag)
        val ndef = Ndef.get(tag)
        if (ndef == null) {
            val info = NfcTagInfo(
                uid = id,
                technologies = tag.techList.map { it.substringAfterLast('.') }.sorted(),
                ndefSupported = NdefFormatable.get(tag) != null,
                formatted = false,
                writable = null,
                maxSizeBytes = null,
                ndefType = null,
                storedPayload = null
            )
            return NfcResult.Read(info, null)
        }

        return try {
            ndef.connect()
            val message = ndef.ndefMessage
            val stored = message?.records
                ?.asSequence()
                ?.mapNotNull(::parseRecord)
                ?.firstOrNull()
            val info = NfcTagInfo(
                uid = id,
                technologies = tag.techList.map { it.substringAfterLast('.') }.sorted(),
                ndefSupported = true,
                formatted = message != null,
                writable = ndef.isWritable,
                maxSizeBytes = ndef.maxSize,
                ndefType = ndef.type,
                storedPayload = stored,
                hasBeeKeepLaunchRecord = message?.records?.any(::isBeeKeepAppRecord) == true
            )
            NfcResult.Read(info, stored)
        } finally {
            runCatching { ndef.close() }
        }
    }

    private fun write(tag: Tag, text: String, allowOverwriteOtherHive: Boolean): NfcResult {
        val id = uid(tag)
        if (text.isBlank()) return NfcResult.Error("There is no hive payload to write.")
        val payload = text.trim().toByteArray(StandardCharsets.UTF_8)
        val mimeRecord = NdefRecord.createMime(BeeKeepNfcPayload.MIME_TYPE, payload)
        // The Android Application Record asks Android to launch BeeKeep for this tag.
        val appRecord = NdefRecord.createApplicationRecord("com.beekeep.app")
        val message = NdefMessage(arrayOf(mimeRecord, appRecord))

        try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                try {
                    ndef.connect()
                    if (!ndef.isWritable) return NfcResult.Error("This NFC tag is read-only.")
                    if (message.toByteArray().size > ndef.maxSize) return NfcResult.Error("The hive tag is too small for BeeKeep data.")
                    val existing = ndef.ndefMessage?.records
                        ?.asSequence()
                        ?.mapNotNull(::parseRecord)
                        ?.firstOrNull()
                    val existingHiveId = BeeKeepNfcPayload.hiveId(existing)
                    val requestedHiveId = BeeKeepNfcPayload.hiveId(text)
                    if (!allowOverwriteOtherHive && existingHiveId != null && requestedHiveId != null && existingHiveId != requestedHiveId) {
                        return NfcResult.Error("This tag is already assigned to another hive. Choose REPLACE to overwrite it.")
                    }
                    ndef.writeNdefMessage(message)
                    val confirmed = ndef.ndefMessage?.records
                        ?.asSequence()
                        ?.mapNotNull(::parseRecord)
                        ?.firstOrNull()
                    if (confirmed != text) {
                        return NfcResult.Error("The tag reported a write, but BeeKeep could not verify the data. Try again.")
                    }
                    return NfcResult.Written(tagInfoAfterWrite(tag, id, text, ndef))
                } finally {
                    runCatching { ndef.close() }
                }
            }

            val formattable = NdefFormatable.get(tag)
                ?: return NfcResult.Error("This tag cannot store NDEF data.")
            try {
                formattable.connect()
                formattable.format(message)
                val info = NfcTagInfo(
                    uid = id,
                    technologies = tag.techList.map { it.substringAfterLast('.') }.sorted(),
                    ndefSupported = true,
                    formatted = true,
                    writable = true,
                    maxSizeBytes = null,
                    ndefType = null,
                    storedPayload = text,
                    hasBeeKeepLaunchRecord = true
                )
                val verify = Ndef.get(tag)?.let { verification ->
                    runCatching {
                        verification.connect()
                        verification.ndefMessage?.records?.asSequence()?.mapNotNull(::parseRecord)?.firstOrNull() == text
                    }.getOrDefault(false).also { runCatching { verification.close() } }
                } ?: false
                if (!verify) return NfcResult.Error("The tag was formatted, but BeeKeep could not verify the payload. Try again.")
                return NfcResult.Written(info)
            } finally {
                runCatching { formattable.close() }
            }
        } catch (e: Exception) {
            return NfcResult.Error(friendlyError(e))
        }
    }

    private fun tagInfoAfterWrite(tag: Tag, id: String, text: String, ndef: Ndef): NfcTagInfo = NfcTagInfo(
        uid = id,
        technologies = tag.techList.map { it.substringAfterLast('.') }.sorted(),
        ndefSupported = true,
        formatted = true,
        writable = ndef.isWritable,
        maxSizeBytes = ndef.maxSize,
        ndefType = ndef.type,
        storedPayload = text,
        hasBeeKeepLaunchRecord = true
    )

    private fun prepareTagForBeeKeep(tag: Tag, result: NfcResult.Read): NfcResult.Read {
        if (!knownHiveTagsReady) {
            return result.copy(launchPreparationError = "BeeKeep is still loading hive assignments. Scan the tag again in a moment.")
        }

        val uidKey = result.uid.trim().uppercase(java.util.Locale.ROOT)
        val assignedHiveId = knownHiveIdByTagUid[uidKey]
        val payloadHiveId = BeeKeepNfcPayload.hiveId(result.text)

        // A UID/payload conflict must never change the hive data. If needed, rewrite
        // the same stored payload only to add BeeKeep's Android launch record.
        if (assignedHiveId != null && payloadHiveId != null && assignedHiveId != payloadHiveId) {
            val warning = "This tag's stored hive payload conflicts with its saved assignment. The payload was preserved."
            if (result.info.hasBeeKeepLaunchRecord) return result.copy(launchPreparationError = warning)
            return when (val launchWrite = write(tag, result.text!!.trim(), allowOverwriteOtherHive = false)) {
                is NfcResult.Written -> NfcResult.Read(launchWrite.info, result.text.trim(), warning)
                is NfcResult.Error -> result.copy(launchPreparationError = "$warning BeeKeep launch data could not be added: ${launchWrite.message}")
                is NfcResult.Read -> result.copy(launchPreparationError = warning)
            }
        }

        val targetPayload = when {
            assignedHiveId != null -> BeeKeepNfcPayload.forHive(assignedHiveId)
            payloadHiveId != null -> result.text!!.trim() // preserve a valid hive payload, even if this device has not synced that hive yet
            else -> BeeKeepNfcPayload.UNASSIGNED_PAYLOAD
        }

        if (result.info.hasBeeKeepLaunchRecord && result.text?.trim() == targetPayload) return result

        return when (val written = write(tag, targetPayload, allowOverwriteOtherHive = false)) {
            is NfcResult.Written -> NfcResult.Read(written.info, targetPayload)
            is NfcResult.Error -> result.copy(launchPreparationError = written.message)
            is NfcResult.Read -> result
        }
    }

    private fun isBeeKeepAppRecord(record: NdefRecord): Boolean =
        record.tnf == NdefRecord.TNF_EXTERNAL_TYPE &&
            record.type.toString(StandardCharsets.UTF_8).equals("android.com:pkg", ignoreCase = true) &&
            record.payload.toString(StandardCharsets.UTF_8).equals("com.beekeep.app", ignoreCase = true)

    private fun parseRecord(record: NdefRecord): String? {
        if (record.tnf == NdefRecord.TNF_MIME_MEDIA &&
            record.type.toString(StandardCharsets.US_ASCII).equals(BeeKeepNfcPayload.MIME_TYPE, ignoreCase = true)) {
            return record.payload.toString(StandardCharsets.UTF_8)
        }
        if (record.tnf == NdefRecord.TNF_WELL_KNOWN && record.type.contentEquals(NdefRecord.RTD_TEXT)) {
            return parseTextRecord(record)
        }
        return null
    }

    private fun parseTextRecord(record: NdefRecord): String? {
        val payload = record.payload
        if (payload.isEmpty()) return null
        return try {
            val status = payload[0].toInt() and 0xFF
            val languageLength = status and 0x3F
            val charset = if ((status and 0x80) == 0) StandardCharsets.UTF_8 else Charsets.UTF_16
            if (1 + languageLength > payload.size) return null
            String(payload, 1 + languageLength, payload.size - 1 - languageLength, charset)
        } catch (_: Exception) {
            null
        }
    }

    private fun friendlyError(e: Throwable): String = when (e) {
        is TagLostException -> "The tag moved away. Hold the phone steady and try again."
        is IOException -> "NFC communication failed. Hold the phone against the tag and try again."
        else -> e.message?.takeIf { it.isNotBlank() } ?: "Could not complete the NFC operation."
    }
}
