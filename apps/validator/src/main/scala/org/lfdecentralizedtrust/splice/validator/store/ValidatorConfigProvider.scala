// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import cats.data.OptionT
import cats.implicits.toBifunctorOps
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Codec

import scala.concurrent.Future
import io.circe.generic.semiauto.deriveCodec
import org.lfdecentralizedtrust.splice.store.KeyValueStore

import scala.concurrent.ExecutionContext

class ValidatorConfigProvider(config: KeyValueStore, val loggerFactory: NamedLoggerFactory)
    extends NamedLogging {

  private val scanInternalConfigKey = "validator_scan_internal_config_key"
  private val migratingPartiesInternalConfigKey = "validator_recovery_migrating_parties"
  import ValidatorConfigProvider.*

  final def setScanUrlInternalConfig(
      value: Seq[ScanUrlInternalConfig]
  )(implicit tc: TraceContext): Future[Unit] = {
    config.setValue(scanInternalConfigKey, value)
  }

  final def getScanUrlInternalConfig(
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): OptionT[Future, Seq[ScanUrlInternalConfig]] = {
    config.readValueAndLogOnDecodingFailure(scanInternalConfigKey)
  }

  def clearPartiesToMigrate()(implicit tc: TraceContext): Future[Unit] = {
    logger.info("Clearing parties that were migrated")
    config.deleteKey(migratingPartiesInternalConfigKey)
  }

  def getPartiesToMigrate()(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): OptionT[Future, Set[PartyId]] = {
    config.readValueAndLogOnDecodingFailure[PartiesBeingMigrated](migratingPartiesInternalConfigKey).map(
      _.parties
    )
  }

  def setPartiesToMigrate(parties: Set[PartyId])(implicit tc: TraceContext): Future[Unit] = {
    logger.info(s"Storing parties that will be migrated $parties")
    config.setValue(migratingPartiesInternalConfigKey, PartiesBeingMigrated(parties))
  }
}

object ValidatorConfigProvider {
  final case class ScanUrlInternalConfig(
      svName: String,
      url: String,
  )
  final case class PartiesBeingMigrated(
      parties: Set[PartyId]
  )

  implicit val partyIdCodec: Codec[PartyId] =
    Codec
      .from[String](implicitly, implicitly)
      .iemap(partyId => PartyId.fromProtoPrimitive(partyId, "party").leftMap(_.message))(
        _.toProtoPrimitive
      )
  implicit val scanUrlCodec: Codec[ScanUrlInternalConfig] = deriveCodec[ScanUrlInternalConfig]
  implicit val partiesBeingMigratedCodec: Codec[PartiesBeingMigrated] =
    deriveCodec[PartiesBeingMigrated]

}
