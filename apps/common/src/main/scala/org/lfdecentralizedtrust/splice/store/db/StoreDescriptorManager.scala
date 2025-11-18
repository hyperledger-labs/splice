// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import io.circe.Json
import org.lfdecentralizedtrust.splice.store.StoreErrors

/** Identifies an instance of a store.
  *
  *  @param version    The version of the store.
  *                    Bumping this number will cause the store to forget all previously ingested data
  *                    and start from a clean state.
  *                    Bump this number whenever you make breaking changes in the ingestion filter or
  *                    TxLog parser, or if you want to reset the store after fixing a bug that lead to
  *                    data corruption.
  * @param name        The name of the store, usually the simple name of the corresponding scala class.
  * @param party       The party that owns the store (i.e., the party that subscribes
  *                    to the update stream that feeds the store).
  * @param participant The participant that serves the update stream that feeds this store.
  * @param key         A set of named values that are used to filter the update stream or
  *                    can otherwise be used to distinguish between different instances of the store.
  */
case class StoreDescriptor(
    version: Int,
    name: String,
    party: PartyId,
    participant: ParticipantId,
    key: Map[String, String],
) {
  def toJson: io.circe.Json = {
    Json.obj(
      "version" -> Json.fromInt(version),
      "name" -> Json.fromString(name),
      "party" -> Json.fromString(party.toProtoPrimitive),
      "participant" -> Json.fromString(participant.toProtoPrimitive),
      "key" -> Json.obj(key.map { case (k, v) => k -> Json.fromString(v) }.toSeq*),
    )
  }
}

object StoreDescriptorManager extends StoreErrors {
//  sealed trait InitializeDescriptorResult[StoreId]
//  case class StoreHasData[StoreId](
//                                    storeId: StoreId,
//                                    lastIngestedOffset: Long,
//                                  ) extends InitializeDescriptorResult[StoreId]
//  case class StoreHasNoData[StoreId](
//                                      storeId: StoreId
//                                    ) extends InitializeDescriptorResult[StoreId]
//  case class StoreNotUsed[StoreId]() extends InitializeDescriptorResult[StoreId]
//
//  /** Initializes the store descriptor in the database and retrieves the last ingested offset.
//   *
//   * This method is idempotent: it inserts the descriptor if it doesn't exist, retrieves its ID,
//   * ensures an entry exists in `store_last_ingested_offsets` for the current migration ID,
//   * and then reads the last ingested offset for that store/migration pair.
//   *
//   * @param descriptor The descriptor defining the store instance.
//   * @param storage The database storage instance to run queries against.
//   * @param domainMigrationId The current domain migration ID.
//   * @param loggerFactory A factory for creating named loggers, used by DbStorage.
//   * @return A Future containing the result, indicating if the store has data and its last ingested offset.
//   */
//  def initializeDescriptor(
//                            descriptor: StoreDescriptor,
//                            storage: DbStorage,
//                            domainMigrationId: Long,
//                          )(implicit
//                            ec: ExecutionContext,
//                            traceContext: TraceContext,
//                          ): Future[InitializeDescriptorResult[Int]] = {
//    // We use a simple import here to ensure SQL interpolation works without mixing in a full profile
//    import storage.profile.api.*
//
//    // Notes:
//    // - Postgres JSONB does not preserve white space, does not preserve the order of object keys, and does not keep duplicate object keys
//    // - Postgres JSONB columns have a maximum size of 255MB
//    // - We are using noSpacesSortKeys to insert a canonical serialization of the JSON object, even though this is not necessary for Postgres
//    // - 'ON CONFLICT DO NOTHING RETURNING ...' does not return anything if the row already exists, that's why we are using two separate queries
//    val descriptorStr = String256M.tryCreate(descriptor.toJson.noSpacesSortKeys)
//
//    for {
//      // 1. Insert the descriptor (if new)
//      _ <- storage
//        .update(
//          sql"""
//          insert into store_descriptors (descriptor)
//          values (${descriptorStr}::jsonb)
//          on conflict do nothing
//         """.asUpdate,
//          "initializeDescriptor.1",
//        )
//
//      // 2. Select the ID of the descriptor (whether newly inserted or existing)
//      newStoreId <- storage
//        .querySingle(
//          sql"""
//           select id
//           from store_descriptors
//           where descriptor = ${descriptorStr}::jsonb
//           """.as[Int].headOption,
//          "initializeDescriptor.2",
//        )
//        .getOrRaise(
//          new RuntimeException(s"No row for $descriptor found, which was just inserted!")
//        )
//
//      // 3. Ensure an entry exists for the store/migration pair to track the offset
//      _ <- storage
//        .update(
//          sql"""
//           insert into store_last_ingested_offsets (store_id, migration_id)
//           values (${newStoreId}, ${domainMigrationId})
//           on conflict do nothing
//           """.asUpdate,
//          "initializeDescriptor.3",
//        )
//
//      // 4. Retrieve the last ingested offset
//      lastIngestedOffset <- storage
//        .querySingle(
//          sql"""
//           select last_ingested_offset
//           from store_last_ingested_offsets
//           where store_id = ${newStoreId} and migration_id = $domainMigrationId
//           """.as[Option[String]].headOption,
//          "initializeDescriptor.4",
//        )
//        .getOrRaise(
//          new RuntimeException(s"No row for $newStoreId found, which was just inserted!")
//        )
//        .map(_.map(LegacyOffset.Api.assertFromStringToLong(_)))
//    } yield lastIngestedOffset match {
//      case Some(offset) => StoreHasData(newStoreId, offset)
//      case None => StoreHasNoData(newStoreId)
//    }
//  }
}
