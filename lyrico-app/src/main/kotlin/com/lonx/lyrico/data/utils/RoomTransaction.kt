package com.lonx.lyrico.data.utils

import androidx.room.RoomDatabase
import androidx.room.Transactor
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection

/**
 * Runs [block] inside a write transaction.
 *
 * Android used `androidx.room.withTransaction` from `room-ktx`, which has no JVM/KMP artifact: on
 * desktop the equivalent is acquiring a writer connection and opening an explicit transaction on it.
 * [immediateTransaction] issues `BEGIN IMMEDIATE`, matching the semantics Room's Android extension
 * used for write transactions (a deferred transaction that starts by reading and then writes can
 * fail with a lock upgrade error instead of waiting for the writer lock).
 *
 * Suspending DAO calls made inside [block] join this transaction: Room publishes the transaction on
 * the coroutine context, so they reuse the connection instead of taking a second one from the pool.
 * Do not call other DAOs on a *different* [RoomDatabase] instance inside [block].
 */
suspend fun <R> RoomDatabase.inTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor: Transactor -> transactor.immediateTransaction { block() } }
