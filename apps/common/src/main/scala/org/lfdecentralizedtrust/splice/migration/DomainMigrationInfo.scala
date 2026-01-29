// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.migration

import org.lfdecentralizedtrust.splice.environment.{
  BaseLedgerConnection,
  SpliceLedgerConnection,
  RetryFor,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** Holds information about a hard domain migration
  *
  * @param currentMigrationId  The migration id of the current incarnation of the global domain.
  */
final case class DomainMigrationInfo(
    currentMigrationId: Long,
    migrationTimeInfo: Option[MigrationTimeInfo],
)

/** @param acsRecordTime       The record time at which the ACS snapshot was taken on the previous
  *                            incarnation of the global domain.
  *                            None if this is the first incarnation of the global domain.
  * @param synchronizerWasPaused True when we ran through a proper migration, false for disaster recovery
  */
final case class MigrationTimeInfo(
    acsRecordTime: CantonTimestamp,
    synchronizerWasPaused: Boolean,
)

object DomainMigrationInfo {
  def saveToUserMetadata(
      connection: SpliceLedgerConnection,
      user: String,
      info: DomainMigrationInfo,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] =
    connection.ensureUserMetadataAnnotation(
      user,
      Map(
        BaseLedgerConnection.DOMAIN_MIGRATION_ACS_RECORD_TIME_METADATA_KEY -> info.migrationTimeInfo
          .map(_.acsRecordTime)
          .fold("*")(_.toProtoPrimitive.toString),
        BaseLedgerConnection.DOMAIN_MIGRATION_CURRENT_MIGRATION_ID_METADATA_KEY -> info.currentMigrationId.toString,
        BaseLedgerConnection.DOMAIN_MIGRATION_DOMAIN_WAS_PAUSED_KEY -> info.migrationTimeInfo
          .map(_.synchronizerWasPaused)
          .fold("*")(_.toString),
      ),
      RetryFor.WaitingOnInitDependency,
    )

  def loadFromUserMetadata(connection: BaseLedgerConnection, user: String)(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[DomainMigrationInfo] = {
    for {
      acsRecordTime <- connection
        .waitForUserMetadata(
          user,
          BaseLedgerConnection.DOMAIN_MIGRATION_ACS_RECORD_TIME_METADATA_KEY,
        )
        .map {
          case "*" => None
          case s =>
            Some(
              CantonTimestamp
                .fromProtoPrimitive(s.toLong)
                .getOrElse(
                  throw new RuntimeException(s"Failed to parse '$s' as the ACS record time")
                )
            )
        }
      currentMigrationId <- connection
        .waitForUserMetadata(
          user,
          BaseLedgerConnection.DOMAIN_MIGRATION_CURRENT_MIGRATION_ID_METADATA_KEY,
        )
        .map(_.toLong)
      synchronizerWasPaused <- connection
        .waitForUserMetadata(
          user,
          BaseLedgerConnection.DOMAIN_MIGRATION_DOMAIN_WAS_PAUSED_KEY,
        )
        .map {
          case "*" => None
          case s =>
            Some(s.toBoolean)
        }
    } yield DomainMigrationInfo(
      currentMigrationId = currentMigrationId,
      migrationTimeInfo = for {
        recordTime <- acsRecordTime
        wasPaused <- synchronizerWasPaused
      } yield MigrationTimeInfo(recordTime, wasPaused),
    )
  }
}
