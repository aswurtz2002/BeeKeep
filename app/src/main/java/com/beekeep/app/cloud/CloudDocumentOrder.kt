package com.beekeep.app.cloud

/**
 * Cloud results are stored in entity_type order, which is not dependency-aware.
 * Room ignores child rows that reference a hive not yet inserted, and unfinished inspection
 * drafts must be applied after saved inspection history so a completed save can retire stale drafts.
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
        "event" to 4,
        "inspection_form_draft" to 5
    )

    private fun priority(entityType: String): Int = priorities[entityType] ?: 6

    fun personal(documents: Collection<CloudDocumentRow>): List<CloudDocumentRow> =
        documents.sortedWith(compareBy<CloudDocumentRow> { priority(it.entity_type) }.thenBy { it.entity_id })

    fun workspace(documents: Collection<CloudWorkspaceDocumentRow>): List<CloudWorkspaceDocumentRow> =
        documents.sortedWith(compareBy<CloudWorkspaceDocumentRow> { priority(it.entity_type) }.thenBy { it.entity_id })
}
