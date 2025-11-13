// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import cats.data.OptionT
import cats.implicits.{catsSyntaxOptionId, toBifunctorOps}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Decoder}

import scala.concurrent.{ExecutionContext, Future}

class ValidatorConfigProvider(config: ValidatorInternalStore, val loggerFactory: NamedLoggerFactory)
    extends NamedLogging {

  private val scanInternalConfigKey = "validator_scan_internal_config_key"
  private val migratingPartiesInternalConfigKey = "validator_recovery_migrating_parties"
  import ValidatorConfigProvider.*

  final def setScanUrlInternalConfig(
      value: Seq[ScanUrlInternalConfig]
  )(implicit tc: TraceContext): Future[Unit] = {
    config.setConfig(scanInternalConfigKey, value)
  }

  final def getScanUrlInternalConfig(
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): OptionT[Future, Seq[ScanUrlInternalConfig]] = {
    readConfigAndLogOnDecodingFailure(scanInternalConfigKey)
  }

  def clearPartiesToMigrate()(implicit tc: TraceContext): Future[Unit] = {
    logger.info("Clearing parties that were migrated")
    config.deleteConfig(migratingPartiesInternalConfigKey)
  }

  def getPartiesToMigrate()(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): OptionT[Future, Set[PartyId]] = {
    readConfigAndLogOnDecodingFailure[PartiesBeingMigrated](migratingPartiesInternalConfigKey).map(
      _.parties
    )
  }

  private def readConfigAndLogOnDecodingFailure[T: Decoder](
      key: String
  )(implicit tc: TraceContext, ec: ExecutionContext) = {
    config
      .getConfig[T](key)
      .subflatMap(
        _.fold(
          { failure =>
            logger.warn(s"Failed to decode $key from the db $failure")
            None
          },
          _.some,
        )
      )
  }

  def setPartiesToMigrate(parties: Set[PartyId])(implicit tc: TraceContext): Future[Unit] = {
    logger.info(s"Storing parties that will be migrated $parties")
    config.setConfig(migratingPartiesInternalConfigKey, PartiesBeingMigrated(parties))
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
