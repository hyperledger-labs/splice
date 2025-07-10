// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import cats.data.OptionT
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import org.lfdecentralizedtrust.splice.config.SpliceInstanceNamesConfig
import org.lfdecentralizedtrust.tokenstandard.metadata.v1
import org.lfdecentralizedtrust.splice.scan.store.ScanStore

import java.time.ZoneOffset
import scala.concurrent.{ExecutionContext, Future}

class HttpTokenStandardMetadataHandler(
    store: ScanStore,
    spliceInstanceNames: SpliceInstanceNamesConfig,
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

  private def lookupTotalSupply()(implicit ec: ExecutionContext, tc: TraceContext) = (
    for {
      (latestRoundNr, effectiveAt) <- OptionT(store.lookupRoundOfLatestData())
      totalSupply <- OptionT.liftF(store.getTotalAmuletBalance(latestRoundNr))
    } yield (totalSupply, effectiveAt)
  ).value

  private def getAmuletInstrument()(implicit ec: ExecutionContext, tc: TraceContext) =
    for {
      optSupply <- lookupTotalSupply()
    } yield v1.definitions.Instrument(
      id = "Amulet",
      name = spliceInstanceNames.amuletName,
      symbol = spliceInstanceNames.amuletNameAcronym,
      decimals = 10,
      totalSupply = optSupply.map(_._1.toString()),
      totalSupplyAsOf = optSupply.map(_._2.atOffset(ZoneOffset.UTC)),
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
