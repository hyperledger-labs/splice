// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import cats.data.OptionT
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import org.lfdecentralizedtrust.splice.config.SpliceInstanceNamesConfig
import org.lfdecentralizedtrust.splice.environment.PackageVersionSupport
import org.lfdecentralizedtrust.splice.scan.admin.http.HttpTokenStandardMetadataHandler.TotalSupply
import org.lfdecentralizedtrust.tokenstandard.metadata.v1
import org.lfdecentralizedtrust.splice.scan.store.{AcsSnapshotStore, ScanStore}

import java.time.{Instant, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future}

class HttpTokenStandardMetadataHandler(
    store: ScanStore,
    acsSnapshotStore: AcsSnapshotStore,
    spliceInstanceNames: SpliceInstanceNamesConfig,
    packageVersionSupport: PackageVersionSupport,
    clock: Clock,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends v1.Handler[TraceContext]
    with Spanning
    with NamedLogging {

  def getRegistryInfo(
      respond: v1.Resource.GetRegistryInfoResponse.type
  )()(tc: TraceContext): Future[v1.Resource.GetRegistryInfoResponse] =
    Future.successful(
      v1.Resource.GetRegistryInfoResponse.OK(
        v1.definitions.GetRegistryInfoResponse(
          adminId = store.key.dsoParty.toProtoPrimitive,
          supportedApis = Map("splice-api-token-metadata-v1" -> 1),
        )
      )
    )

  def listInstruments(
      respond: v1.Resource.ListInstrumentsResponse.type
  )(pageSize: Option[Int], pageToken: Option[String])(
      extracted: TraceContext
  ): Future[v1.Resource.ListInstrumentsResponse] = {
    implicit val tc: TraceContext = extracted
    for { instrument <- getAmuletInstrument() } yield v1.Resource.ListInstrumentsResponse.OK(
      v1.definitions.ListInstrumentsResponse(instruments = Vector(instrument))
    )
  }
  def getInstrument(
      respond: v1.Resource.GetInstrumentResponse.type
  )(instrumentId: String)(
      extracted: TraceContext
  ): Future[v1.Resource.GetInstrumentResponse] = {
    implicit val tc: TraceContext = extracted
    for { instrument <- getAmuletInstrument() } yield
      if (instrumentId == instrument.id) {
        v1.Resource.GetInstrumentResponse.OK(instrument)
      } else {
        v1.Resource.GetInstrumentResponse.NotFound(
          v1.definitions.ErrorResponse(s"No instrument with id $instrumentId found")
        )
      }
  }

  private def lookupTotalSupplyByLatestRound()(implicit ec: ExecutionContext, tc: TraceContext) =
    for {
      (latestRoundNr, effectiveAt) <- OptionT(store.lookupRoundOfLatestData())
      totalSupply <- OptionT.liftF(store.getTotalAmuletBalance(latestRoundNr))
    } yield TotalSupply(amount = totalSupply, asOfTimestamp = effectiveAt)

  private def lookupTotalSupplyByLatestAcsSnapshot()(implicit tc: TraceContext) = {
    for {
      latestSnapshot <- OptionT(
        acsSnapshotStore.lookupSnapshotBefore(
          acsSnapshotStore.currentMigrationId,
          CantonTimestamp.now(),
        )
      )
      unlocked <- OptionT.fromOption[Future](latestSnapshot.unlockedAmuletBalance)
      locked <- OptionT.fromOption[Future](latestSnapshot.lockedAmuletBalance)
    } yield TotalSupply(
      amount = locked + unlocked,
      asOfTimestamp = latestSnapshot.snapshotRecordTime.toInstant,
    )
  }

  private def lookupTotalSupply()(implicit tc: TraceContext) = {
    for {
      noHoldingFeesOnTransfers <- packageVersionSupport.noHoldingFeesOnTransfers(
        store.key.dsoParty,
        clock.now,
      )
      deductHoldingFees = !noHoldingFeesOnTransfers.supported
      result <-
        if (deductHoldingFees) {
          lookupTotalSupplyByLatestRound().value
        } else {
          lookupTotalSupplyByLatestAcsSnapshot().orElse(lookupTotalSupplyByLatestRound()).value
        }
    } yield result
  }

  private def getAmuletInstrument()(implicit ec: ExecutionContext, tc: TraceContext) =
    for {
      optSupply <- lookupTotalSupply()
    } yield v1.definitions.Instrument(
      id = "Amulet",
      name = spliceInstanceNames.amuletName,
      symbol = spliceInstanceNames.amuletNameAcronym,
      decimals = 10,
      totalSupply = optSupply.map(_.amount.toString()),
      totalSupplyAsOf = optSupply.map(_.asOfTimestamp.atOffset(ZoneOffset.UTC)),
      supportedApis = Map(
        "splice-api-token-metadata-v1" -> 1,
        "splice-api-token-holding-v1" -> 1,
        "splice-api-token-transfer-instruction-v1" -> 1,
        "splice-api-token-allocation-v1" -> 1,
        "splice-api-token-allocation-instruction-v1" -> 1,
        // No burn-mint API, as that's not used for Amulet
        // No alloation-request API, as that's an API used by apps, not registries
      ),
    )

}

object HttpTokenStandardMetadataHandler {
  case class TotalSupply(amount: BigDecimal, asOfTimestamp: Instant)
}
