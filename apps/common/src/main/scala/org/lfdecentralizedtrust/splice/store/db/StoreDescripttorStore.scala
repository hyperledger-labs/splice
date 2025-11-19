// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.db

import com.digitalasset.canton.config.CantonRequireTypes.String256M
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Json
import org.lfdecentralizedtrust.splice.store.StoreErrors
import org.lfdecentralizedtrust.splice.util.LegacyOffset
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown

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

sealed trait InitializeDescriptorResult[StoreId]
case class StoreHasData[StoreId](
    storeId: StoreId,
    lastIngestedOffset: Long,
) extends InitializeDescriptorResult[StoreId]
case class StoreHasNoData[StoreId](
    storeId: StoreId
) extends InitializeDescriptorResult[StoreId]
case class StoreNotUsed[StoreId]() extends InitializeDescriptorResult[StoreId]

object StoreDescripttorStore extends StoreErrors {
  def initializeDescriptor(
      descriptor: StoreDescriptor,
      storage: DbStorage,
      domainMigrationId: Long,
  )(implicit
      traceContext: TraceContext,
      executionContext: scala.concurrent.ExecutionContext,
      closeContext: CloseContext,
  ): FutureUnlessShutdown[InitializeDescriptorResult[Int]] = {
    // Notes:
    // - Postgres JSONB does not preserve white space, does not preserve the order of object keys, and does not keep duplicate object keys
    // - Postgres JSONB columns have a maximum size of 255MB
    // - We are using noSpacesSortKeys to insert a canonical serialization of the JSON object, even though this is not necessary for Postgres
    // - 'ON CONFLICT DO NOTHING RETURNING ...' does not return anything if the row already exists, that's why we are using two separate queries
    val descriptorStr = String256M.tryCreate(descriptor.toJson.noSpacesSortKeys)
    for {
      _ <- storage
        .update(
          sql"""
            insert into store_descriptors (descriptor)
            values (${descriptorStr}::jsonb)
            on conflict do nothing
           """.asUpdate,
          "initializeDescriptor.1",
        )

      newStoreId <- storage
        .querySingle(
          sql"""
             select id
             from store_descriptors
             where descriptor = ${descriptorStr}::jsonb
             """.as[Int].headOption,
          "initializeDescriptor.2",
        )
        .getOrRaise(
          new RuntimeException(s"No row for $descriptor found, which was just inserted!")
        )

      _ <- storage
        .update(
          sql"""
             insert into store_last_ingested_offsets (store_id, migration_id)
             values (${newStoreId}, ${domainMigrationId})
             on conflict do nothing
             """.asUpdate,
          "initializeDescriptor.3",
        )
      lastIngestedOffset <- storage
        .querySingle(
          sql"""
             select last_ingested_offset
             from store_last_ingested_offsets
             where store_id = ${newStoreId} and migration_id = $domainMigrationId
             """.as[Option[String]].headOption,
          "initializeDescriptor.4",
        )
        .getOrRaise(
          new RuntimeException(s"No row for $newStoreId found, which was just inserted!")
        )
        .map(_.map(LegacyOffset.Api.assertFromStringToLong(_)))
    } yield lastIngestedOffset match {
      case Some(offset) => StoreHasData(newStoreId, offset)
      case None => StoreHasNoData(newStoreId)
    }
  }
}
