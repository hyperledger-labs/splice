package com.daml.network.migration

import com.daml.network.environment.{BaseLedgerConnection, CNLedgerConnection, RetryFor}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** Holds information about a hard domain migration
  *
  * @param currentMigrationId  The migration id of the current incarnation of the global domain.
  * @param acsRecordTime       The record time at which the ACS snapshot was taken on the previous
  *                            incarnation of the global domain.
  *                            None if this is the first incarnation of the global domain.
  * @param domainWasPaused     Whether the previous incarnation of the domain was paused when the ACS
  *                            snapshot was taken.
  *                            True for regular domain migrations, false for disaster recovery.
  */
final case class DomainMigrationInfo(
    currentMigrationId: Long,
    acsRecordTime: Option[CantonTimestamp],
    domainWasPaused: Boolean,
)

object DomainMigrationInfo {
  def saveToUserMetadata(
      connection: CNLedgerConnection,
      user: String,
      info: DomainMigrationInfo,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] =
    connection.ensureUserMetadataAnnotation(
      user,
      Map(
        BaseLedgerConnection.DOMAIN_MIGRATION_ACS_RECORD_TIME_METADATA_KEY -> info.acsRecordTime
          .fold("*")(_.toProtoPrimitive.toString),
        BaseLedgerConnection.DOMAIN_MIGRATION_CURRENT_MIGRATION_ID_METADATA_KEY -> info.currentMigrationId.toString,
        BaseLedgerConnection.DOMAIN_MIGRATION_DOMAIN_WAS_PAUSED_KEY -> info.domainWasPaused.toString,
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
      domainWasPaused <- connection
        .waitForUserMetadata(
          user,
          BaseLedgerConnection.DOMAIN_MIGRATION_DOMAIN_WAS_PAUSED_KEY,
        )
        .map(_.toBoolean)
      currentMigrationId <- connection
        .waitForUserMetadata(
          user,
          BaseLedgerConnection.DOMAIN_MIGRATION_CURRENT_MIGRATION_ID_METADATA_KEY,
        )
        .map(_.toLong)
    } yield DomainMigrationInfo(
      currentMigrationId = currentMigrationId,
      acsRecordTime = acsRecordTime,
      domainWasPaused = domainWasPaused,
    )
  }
}
