// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.http

import org.lfdecentralizedtrust.splice.auth.AuthExtractor.TracedUser
import org.lfdecentralizedtrust.splice.http.v0.definitions.MaybeCachedContractWithState
import org.lfdecentralizedtrust.splice.http.v0.{definitions, scanproxy as v0}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.http.v0.scanproxy.ScanproxyResource

import scala.concurrent.{ExecutionContext, Future}

class HttpScanProxyHandler(
    scanConnection: BftScanConnection,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends v0.ScanproxyHandler[TracedUser]
    with Spanning
    with NamedLogging {

  private val workflowId = this.getClass.getSimpleName

  override def getDsoPartyId(
      respond: v0.ScanproxyResource.GetDsoPartyIdResponse.type
  )()(tUser: TracedUser): Future[v0.ScanproxyResource.GetDsoPartyIdResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.getDsoPartyId") { implicit traceContext => _ =>
      for {
        dsoPartyId <- scanConnection.getDsoPartyId()
      } yield {
        respond.OK(
          definitions.GetDsoPartyIdResponse(dsoPartyId.toProtoPrimitive)
        )
      }
    }
  }

  override def getDsoInfo(
      respond: v0.ScanproxyResource.GetDsoInfoResponse.type
  )()(tUser: TracedUser): Future[v0.ScanproxyResource.GetDsoInfoResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.getDsoInfo") { implicit traceContext => _ =>
      for {
        dsoInfo <- scanConnection.getDsoInfo()
      } yield {
        respond.OK(
          dsoInfo
        )
      }
    }
  }

  override def lookupFeaturedAppRight(
      respond: v0.ScanproxyResource.LookupFeaturedAppRightResponse.type
  )(
      providerPartyId: String
  )(tUser: TracedUser): Future[v0.ScanproxyResource.LookupFeaturedAppRightResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.lookupFeaturedAppRight") { implicit traceContext => _ =>
      for {
        featuredAppRight <- scanConnection.lookupFeaturedAppRight(
          PartyId.tryFromProtoPrimitive(providerPartyId)
        )
      } yield {
        respond.OK(
          definitions.LookupFeaturedAppRightResponse(featuredAppRight.map(_.toHttp))
        )
      }
    }
  }

  override def getOpenAndIssuingMiningRounds(
      respond: v0.ScanproxyResource.GetOpenAndIssuingMiningRoundsResponse.type
  )(
  )(tUser: TracedUser): Future[v0.ScanproxyResource.GetOpenAndIssuingMiningRoundsResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.getOpenAndIssuingMiningRounds") { implicit traceContext => _ =>
      for {
        (open, issuing) <- scanConnection.getOpenAndIssuingMiningRounds()
      } yield {
        respond.OK(
          definitions.GetOpenAndIssuingMiningRoundsProxyResponse(
            openMiningRounds = open.map(_.toHttp).toVector,
            issuingMiningRounds = issuing.map(_.toHttp).toVector,
          )
        )
      }
    }
  }

  override def getAmuletRules(respond: v0.ScanproxyResource.GetAmuletRulesResponse.type)()(
      tUser: TracedUser
  ): Future[v0.ScanproxyResource.GetAmuletRulesResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.getAmuletRules") { implicit traceContext => _ =>
      for {
        amuletRules <- scanConnection.getAmuletRulesWithState()
      } yield {
        respond.OK(definitions.GetAmuletRulesProxyResponse(amuletRules.toHttp))
      }
    }
  }

  override def lookupAnsEntryByParty(
      respond: v0.ScanproxyResource.LookupAnsEntryByPartyResponse.type
  )(
      party: String
  )(tUser: TracedUser): Future[v0.ScanproxyResource.LookupAnsEntryByPartyResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.lookupAnsEntryByParty") { implicit traceContext => _ =>
      for {
        entry <- scanConnection.lookupAnsEntryByParty(PartyId.tryFromProtoPrimitive(party))
      } yield {
        entry match {
          case Some(value) =>
            respond.OK(definitions.LookupEntryByPartyResponse(value))
          case None =>
            respond.NotFound(definitions.ErrorResponse(s"Party $party does not have any ANSEntry."))
        }

      }
    }
  }

  override def lookupAnsEntryByName(
      respond: v0.ScanproxyResource.LookupAnsEntryByNameResponse.type
  )(name: String)(tUser: TracedUser): Future[v0.ScanproxyResource.LookupAnsEntryByNameResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.lookupAnsEntryByParty") { implicit traceContext => _ =>
      for {
        entry <- scanConnection.lookupAnsEntryByName(name)
      } yield {
        entry match {
          case Some(value) =>
            respond.OK(definitions.LookupEntryByNameResponse(value))
          case None =>
            respond.NotFound(definitions.ErrorResponse(s"Did not find a AnsEntry with name $name"))
        }

      }
    }
  }

  override def listAnsEntries(
      respond: v0.ScanproxyResource.ListAnsEntriesResponse.type
  )(namePrefix: Option[String], pageSize: Int)(
      tUser: TracedUser
  ): Future[v0.ScanproxyResource.ListAnsEntriesResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.lookupAnsEntryByParty") { implicit traceContext => _ =>
      for {
        entries <- scanConnection.listAnsEntries(namePrefix, pageSize)
      } yield {
        respond.OK(definitions.ListEntriesResponse(entries.toVector))
      }
    }
  }
  def getAnsRules(
      respond: v0.ScanproxyResource.GetAnsRulesResponse.type
  )(
      body: org.lfdecentralizedtrust.splice.http.v0.definitions.GetAnsRulesRequest
  )(tUser: TracedUser): Future[v0.ScanproxyResource.GetAnsRulesResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.getAnsRules") { implicit traceContext => _ =>
      for {
        response <- scanConnection.getAnsRules()
        maybeSynchronizerId = response.state.fold(dId => Some(dId.toProtoPrimitive), None)
        maybeCachedContract = MaybeCachedContractWithState(
          Some(response.contract.toHttp),
          maybeSynchronizerId,
        )
      } yield {
        respond.OK(definitions.GetAnsRulesResponse(ansRulesUpdate = maybeCachedContract))
      }
    }
  }

  override def lookupTransferPreapprovalByParty(
      respond: ScanproxyResource.LookupTransferPreapprovalByPartyResponse.type
  )(party: String)(
      tUser: TracedUser
  ): Future[ScanproxyResource.LookupTransferPreapprovalByPartyResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.lookupTransferPreapprovalByParty") { implicit traceContext => _ =>
      for {
        transferPreapprovalOpt <- scanConnection.lookupTransferPreapprovalByParty(
          PartyId.tryFromProtoPrimitive(party)
        )
      } yield {
        transferPreapprovalOpt match {
          case None =>
            respond.NotFound(
              definitions.ErrorResponse(s"No TransferPreapproval found for party: $party")
            )
          case Some(transferPreapproval) =>
            respond.OK(
              definitions.LookupTransferPreapprovalByPartyResponse(transferPreapproval.toHttp)
            )
        }
      }
    }
  }

  override def lookupTransferCommandCounterByParty(
      respond: ScanproxyResource.LookupTransferCommandCounterByPartyResponse.type
  )(party: String)(
      tUser: TracedUser
  ): Future[ScanproxyResource.LookupTransferCommandCounterByPartyResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.lookupTransferCommandCounterByParty") { implicit traceContext => _ =>
      for {
        transferCommandCounterOpt <- scanConnection.lookupTransferCommandCounterByParty(
          PartyId.tryFromProtoPrimitive(party)
        )
      } yield {
        transferCommandCounterOpt match {
          case None =>
            respond.NotFound(
              definitions.ErrorResponse(
                s"No TransferCommandCounter found for party: $party, use 0 for the nonce"
              )
            )
          case Some(transferCommandCounter) =>
            respond.OK(
              definitions.LookupTransferCommandCounterByPartyResponse(transferCommandCounter.toHttp)
            )
        }
      }
    }
  }

  override def lookupTransferCommandStatus(
      respond: ScanproxyResource.LookupTransferCommandStatusResponse.type
  )(sender: String, nonce: Long)(
      tUser: TracedUser
  ): Future[ScanproxyResource.LookupTransferCommandStatusResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.lookupTransferCommandStatus") { implicit traceContext => _ =>
      val senderParty = PartyId.tryFromProtoPrimitive(sender)
      for {
        statusResponseOpt <- scanConnection.lookupTransferCommandStatus(senderParty, nonce)
      } yield {
        statusResponseOpt match {
          case None =>
            respond.NotFound(
              definitions.ErrorResponse(
                s"Couldn't find transfer command for sender $senderParty with nonce $nonce created in the last 24h"
              )
            )
          case Some(response) => respond.OK(response)
        }
      }
    }
  }
}
