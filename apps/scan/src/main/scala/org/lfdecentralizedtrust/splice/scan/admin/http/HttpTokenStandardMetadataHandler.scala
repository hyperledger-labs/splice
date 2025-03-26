// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import org.lfdecentralizedtrust.tokenstandard.metadata.v1
import org.lfdecentralizedtrust.splice.scan.store.ScanStore

import scala.concurrent.Future

class HttpTokenStandardMetadataHandler(
    store: ScanStore,
    protected val loggerFactory: NamedLoggerFactory,
)(
) extends v1.Handler[TraceContext]
    with Spanning
    with NamedLogging {

  def getRegistryInfo(
      respond: v1.Resource.GetRegistryInfoResponse.type
  )()(tc: TraceContext): Future[v1.Resource.GetRegistryInfoResponse] =
    Future.successful(
      v1.Resource.GetRegistryInfoResponse.OK(
        v1.definitions.GetRegistryInfoResponse(
          providerId = store.key.dsoParty.toProtoPrimitive,
          // TODO(#17943) Add allocation standards once supported
          supportedStandards = Vector("metadata=1.0", "holding=1.0", "transfer-instruction=1.0"),
        )
      )
    )
  def listInstruments(
      respond: v1.Resource.ListInstrumentsResponse.type
  )(pageSize: Option[Int], pageToken: Option[String])(
      tc: TraceContext
  ): Future[v1.Resource.ListInstrumentsResponse] =
    Future.successful(
      v1.Resource.ListInstrumentsResponse.OK(
        v1.definitions.ListInstrumentsResponse(
          instruments = Vector(amuletInstrument)
        )
      )
    )
  def getInstrument(
      respond: v1.Resource.GetInstrumentResponse.type
  )(instrumentId: String)(tc: TraceContext): Future[v1.Resource.GetInstrumentResponse] =
    if (instrumentId == amuletInstrument.instrumentId.id) {
      Future.successful(
        v1.Resource.GetInstrumentResponse.OK(
          amuletInstrument
        )
      )
    } else {
      Future.successful(
        v1.Resource.GetInstrumentResponse.NotFound(
          v1.definitions.ErrorResponse(s"No instrument with id $instrumentId found")
        )
      )
    }

  val amuletInstrument = v1.definitions.Instrument(
    instrumentId = v1.definitions.InstrumentId(
      providerId = store.key.dsoParty.toProtoPrimitive,
      id = "Amulet",
    ),
    name = "Amulet",
    symbol = "Amulet",
  )
}
