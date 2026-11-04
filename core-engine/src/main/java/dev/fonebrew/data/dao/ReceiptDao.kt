package dev.fonebrew.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.fonebrew.data.entity.ReceiptEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room access for the append-only receipt store ([ReceiptEntity]). Same shape as [LedgerDao]:
 * append-only (insert, no update/delete), plus the lookups a receipt store specifically needs
 * that a ledger does not — find-by-idempotency-key (so a caller can detect "this exact retry was
 * already recorded" before inserting a duplicate, per FB-RAT-COM-006) and find-by-object-id (the
 * receipt history for one durable object).
 */
@Dao
interface ReceiptDao {

    /** Append one receipt. Suspends; never updates or deletes an existing row. */
    @Insert
    suspend fun insert(e: ReceiptEntity)

    /** The full receipt store in creation order, re-emitted on every append. */
    @Query("SELECT * FROM receipts ORDER BY createdAtUtcMillis ASC")
    fun all(): Flow<List<ReceiptEntity>>

    /**
     * The most recent receipt already recorded for [idempotencyKey], if any — the idempotency
     * check a caller runs BEFORE inserting a new receipt for a retried operation
     * (FB-RAT-COM-006: "retries use idempotency keys"). Returns the newest match
     * (`LIMIT 1 ... DESC`) in the rare case more than one row somehow shares a key.
     */
    @Query("SELECT * FROM receipts WHERE idempotencyKey = :idempotencyKey ORDER BY createdAtUtcMillis DESC LIMIT 1")
    suspend fun findByIdempotencyKey(idempotencyKey: String): ReceiptEntity?

    /** Every receipt recorded for one durable object, oldest first. */
    @Query("SELECT * FROM receipts WHERE objectId = :objectId ORDER BY createdAtUtcMillis ASC")
    fun forObjectId(objectId: String): Flow<List<ReceiptEntity>>
}
