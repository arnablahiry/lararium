package com.familydocs.app.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM account WHERE id = 1") fun account(): Flow<AccountEntity?>
    @Query("SELECT * FROM account WHERE id = 1") suspend fun accountNow(): AccountEntity?
    @Upsert suspend fun upsertAccount(a: AccountEntity)

    @Query("SELECT * FROM members ORDER BY shared DESC, isSelf DESC, createdAt") fun members(): Flow<List<MemberEntity>>
    @Query("SELECT * FROM members ORDER BY shared DESC, isSelf DESC, createdAt") suspend fun membersNow(): List<MemberEntity>
    @Query("SELECT * FROM members WHERE id = :id") fun member(id: String): Flow<MemberEntity?>
    @Query("SELECT * FROM members WHERE id = :id") suspend fun memberNow(id: String): MemberEntity?
    @Query("SELECT * FROM members WHERE isSelf = 1 LIMIT 1") suspend fun selfNow(): MemberEntity?
    @Query("SELECT * FROM members WHERE isSelf = 1 LIMIT 1") fun self(): Flow<MemberEntity?>
    @Query("SELECT * FROM members WHERE shared = 1 LIMIT 1") suspend fun sharedNow(): MemberEntity?
    @Query("SELECT * FROM members WHERE shared = 1 AND groupId IS :g LIMIT 1") suspend fun sharedIn(g: String?): MemberEntity?
    /** People of group [g]: you (in every group) plus that group's people. */
    @Query("SELECT * FROM members WHERE isSelf = 1 OR groupId IS :g ORDER BY shared DESC, isSelf DESC, createdAt") fun membersIn(g: String?): Flow<List<MemberEntity>>

    // ---- groups ----
    @Query("SELECT * FROM vault_groups ORDER BY createdAt") fun groups(): Flow<List<GroupEntity>>
    @Query("SELECT * FROM vault_groups ORDER BY createdAt") suspend fun groupsNow(): List<GroupEntity>
    @Upsert suspend fun upsertGroup(g: GroupEntity)
    @Query("DELETE FROM vault_groups") suspend fun clearGroups()
    @Query("SELECT * FROM vault_groups WHERE id = :id") suspend fun groupNow(id: String): GroupEntity?
    @Query("SELECT * FROM vault_groups WHERE id = :id") fun group(id: String): Flow<GroupEntity?>

    // ---- what other accounts share with you (read-only here) ----
    @Query("SELECT * FROM members WHERE origin = :key AND groupId IS :g") suspend fun receivedMembers(key: String, g: String): List<MemberEntity>
    @Query("SELECT * FROM documents WHERE origin = :key AND id LIKE :prefix") suspend fun receivedDocuments(key: String, prefix: String): List<DocumentEntity>
    @Query("SELECT * FROM passwords WHERE origin = :key AND id LIKE :prefix") suspend fun receivedPasswords(key: String, prefix: String): List<PasswordEntity>
    @Upsert suspend fun upsertMember(m: MemberEntity)
    @Query("DELETE FROM members WHERE id = :id") suspend fun deleteMemberRow(id: String)
    @Query("DELETE FROM documents WHERE memberId = :id") suspend fun deleteDocumentsOf(id: String)
    @Query("DELETE FROM passwords WHERE memberId = :id") suspend fun deletePasswordsOf(id: String)

    @Transaction
    suspend fun deleteMember(id: String) { deleteDocumentsOf(id); deletePasswordsOf(id); deleteMemberRow(id) }

    @Query("SELECT * FROM documents WHERE memberId = :memberId AND eventId IS NULL AND parentId IS NULL ORDER BY sortOrder IS NULL, sortOrder, createdAt") fun documents(memberId: String): Flow<List<DocumentEntity>>
    /** The later reports added to document [parentId], newest first. */
    @Query("SELECT * FROM documents WHERE parentId = :parentId ORDER BY createdAt DESC") fun supplements(parentId: String): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents WHERE parentId = :parentId") suspend fun supplementsNow(parentId: String): List<DocumentEntity>
    @Query("UPDATE documents SET sortOrder = :order WHERE id = :id") suspend fun setDocumentOrder(id: String, order: Int)
    @Query("SELECT * FROM documents ORDER BY createdAt") fun allDocuments(): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents") suspend fun allDocumentsNow(): List<DocumentEntity>
    @Query("SELECT * FROM documents WHERE memberId = :memberId") suspend fun documentsNow(memberId: String): List<DocumentEntity>
    @Query("SELECT * FROM documents WHERE id = :id") fun document(id: String): Flow<DocumentEntity?>
    @Query("SELECT * FROM documents WHERE id = :id") suspend fun documentNow(id: String): DocumentEntity?
    @Upsert suspend fun upsertDocument(d: DocumentEntity)
    @Query("DELETE FROM documents WHERE id = :id") suspend fun deleteDocument(id: String)
    @Query("SELECT memberId, COUNT(*) AS n FROM documents WHERE eventId IS NULL AND archivedAt IS NULL AND parentId IS NULL GROUP BY memberId") fun documentCounts(): Flow<List<MemberCount>>

    // ---- events ----
    @Query("SELECT * FROM events ORDER BY startDay") fun events(): Flow<List<EventEntity>>
    @Query("SELECT * FROM events WHERE groupId IS :g ORDER BY startDay") fun eventsIn(g: String?): Flow<List<EventEntity>>
    @Query("SELECT * FROM events") suspend fun eventsNow(): List<EventEntity>
    @Query("SELECT * FROM events WHERE id = :id") fun event(id: String): Flow<EventEntity?>
    @Query("SELECT * FROM events WHERE id = :id") suspend fun eventNow(id: String): EventEntity?
    @Upsert suspend fun upsertEvent(e: EventEntity)
    @Query("DELETE FROM events WHERE id = :id") suspend fun deleteEventRow(id: String)
    @Query("SELECT * FROM documents WHERE eventId = :eventId ORDER BY createdAt") fun eventDocuments(eventId: String): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents WHERE eventId = :eventId") suspend fun eventDocumentsNow(eventId: String): List<DocumentEntity>
    @Query("DELETE FROM documents WHERE eventId = :eventId") suspend fun deleteEventDocuments(eventId: String)
    @Query("DELETE FROM events") suspend fun clearEvents()

    @Query("SELECT * FROM passwords WHERE memberId = :memberId ORDER BY site COLLATE NOCASE") fun passwords(memberId: String): Flow<List<PasswordEntity>>
    @Query("SELECT * FROM passwords") suspend fun allPasswordsNow(): List<PasswordEntity>
    @Upsert suspend fun upsertPassword(p: PasswordEntity)
    @Query("DELETE FROM passwords WHERE id = :id") suspend fun deletePassword(id: String)

    @Query("DELETE FROM documents") suspend fun clearDocuments()
    @Query("DELETE FROM passwords") suspend fun clearPasswords()
    @Query("DELETE FROM members") suspend fun clearMembers()
    @Query("DELETE FROM account") suspend fun clearAccount()

    @Transaction
    suspend fun replaceAll(account: AccountEntity?, members: List<MemberEntity>, docs: List<DocumentEntity>, pws: List<PasswordEntity>, events: List<EventEntity> = emptyList(),
                           groups: List<GroupEntity> = emptyList()) {
        clearEvents(); clearDocuments(); clearPasswords(); clearMembers(); clearGroups()
        groups.forEach { upsertGroup(it) }
        clearAccount()
        account?.let { upsertAccount(it) }
        members.forEach { upsertMember(it) }
        docs.forEach { upsertDocument(it) }
        pws.forEach { upsertPassword(it) }
        events.forEach { upsertEvent(it) }
    }
}
