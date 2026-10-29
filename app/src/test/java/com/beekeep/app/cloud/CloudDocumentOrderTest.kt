package com.beekeep.app.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class CloudDocumentOrderTest {
    @Test
    fun personalDocumentsApplyParentsBeforeAssignmentsAndHistory() {
        val unordered = listOf(
            row("inspection_form_draft", 6),
            row("event", 5),
            row("inspection", 4),
            row("nfc_assignment", 3),
            row("hive", 2),
            row("apiary", 1)
        )
        assertEquals(
            listOf("apiary", "hive", "nfc_assignment", "inspection", "event", "inspection_form_draft"),
            CloudDocumentOrder.personal(unordered).map { it.entity_type }
        )
    }

    @Test
    fun workspaceDocumentsUseTheSameDependencyOrder() {
        val unordered = listOf(
            workspaceRow("inspection_form_draft", 5),
            workspaceRow("feeding", 4),
            workspaceRow("inspection", 6),
            workspaceRow("hive", 2),
            workspaceRow("apiary", 1),
            workspaceRow("nfc_assignment", 3)
        )
        assertEquals(
            listOf("apiary", "hive", "nfc_assignment", "inspection", "feeding", "inspection_form_draft"),
            CloudDocumentOrder.workspace(unordered).map { it.entity_type }
        )
    }

    private fun row(type: String, id: Long) = CloudDocumentRow(
        user_id = "user",
        entity_type = type,
        entity_id = id,
        payload = "{}",
        updated_at = 1L
    )

    private fun workspaceRow(type: String, id: Long) = CloudWorkspaceDocumentRow(
        workspace_id = "workspace",
        entity_type = type,
        entity_id = id,
        payload = "{}",
        updated_at = 1L,
        updated_by = "user"
    )
}
