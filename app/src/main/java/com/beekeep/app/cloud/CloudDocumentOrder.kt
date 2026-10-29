package com.beekeep.app.cloud

/**
 * Cloud results are stored in entity_type order, which places event/history rows before hives.
 * Room ignores children that reference a hive not yet inserted, so always apply records in
 * dependency order to avoid silently dropping those rows on a first sync to a device.
 */
internal object CloudDocumentOrder {
    private val priorities = mapOf(
        "apiary" to 0,
        "hive" to 1,
        "nfc_assignment" to 2,
        "inspection_note_draft" to 3,
        "inspection" to 4,
        "feeding" to 4,
        "treatment" to 4,
        "harvest" to 4,
        "event" to 4
    )

    private fun priority(entityType: String): Int = priorities[entityType] ?: 5

    fun personal(documents: Collection<CloudDocumentRow>): List<CloudDocumentRow> =
        documents.sortedWith(compareBy<CloudDocumentRow> { priority(it.entity_type) }.thenBy { it.entity_id })

    fun workspace(documents: Collection<CloudWorkspaceDocumentRow>): List<CloudWorkspaceDocumentRow> =
        documents.sortedWith(compareBy<CloudWorkspaceDocumentRow> { priority(it.entity_type) }.thenBy { it.entity_id })
}
