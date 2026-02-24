// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.admin.http

import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.http.v0.definitions.MaybeCachedContractWithState
import org.lfdecentralizedtrust.splice.http.v0.{definitions, scanproxy as v0}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.auth.AuthenticationOnlyAuthExtractor.AuthenticatedRequest
import org.lfdecentralizedtrust.splice.http.v0.scanproxy.ScanproxyResource

import scala.concurrent.{ExecutionContext, Future}

class HttpScanProxyHandler(
    scanConnection: BftScanConnection,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends v0.ScanproxyHandler[AuthenticatedRequest]
    with Spanning
    with NamedLogging {

  private val workflowId = this.getClass.getSimpleName

  override def getDsoPartyId(
      respond: v0.ScanproxyResource.GetDsoPartyIdResponse.type
  )()(tUser: AuthenticatedRequest): Future[v0.ScanproxyResource.GetDsoPartyIdResponse] = {
    implicit val AuthenticatedRequest(_, traceContext) = tUser
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
  )()(tUser: AuthenticatedRequest): Future[v0.ScanproxyResource.GetDsoInfoResponse] = {
    implicit val AuthenticatedRequest(_, traceContext) = tUser
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
  )(tUser: AuthenticatedRequest): Future[v0.ScanproxyResource.LookupFeaturedAppRightResponse] = {
    implicit val AuthenticatedRequest(_, traceContext) = tUser
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
  )(
      tUser: AuthenticatedRequest
  ): Future[v0.ScanproxyResource.GetOpenAndIssuingMiningRoundsResponse] = {
    implicit val AuthenticatedRequest(_, traceContext) = tUser
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

  override def getHoldingsSummaryAt(
      respond: v0.ScanproxyResource.GetHoldingsSummaryAtResponse.type
  )(
      body: definitions.HoldingsSummaryRequest
  )(tUser: AuthenticatedRequest): Future[v0.ScanproxyResource.GetHoldingsSummaryAtResponse] = {
    implicit val AuthenticatedRequest(_, traceContext) = tUser
    withSpan(s"$workflowId.getHoldingsSummaryAt") { implicit traceContext => _ =>
      for {
        summaryOpt <- scanConnection.getHoldingsSummaryAt(
          CantonTimestamp.assertFromInstant(body.recordTime.toInstant),
          body.migrationId,
          body.ownerPartyIds.map(PartyId.tryFromProtoPrimitive),
          body.recordTimeMatch,
          body.asOfRound,
        )
      } yield {
        summaryOpt match {
          case Some(summary) => respond.OK(summary)
          case None =>
            respond.NotFound(definitions.ErrorResponse("Summary not found for given parameters"))
        }
      }
    }
  }

  override def getAmuletRules(respond: v0.ScanproxyResource.GetAmuletRulesResponse.type)()(
      tUser: AuthenticatedRequest
  ): Future[v0.ScanproxyResource.GetAmuletRulesResponse] = {
    implicit val AuthenticatedRequest(_, traceContext) = tUser
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
  )(tUser: AuthenticatedRequest): Future[v0.ScanproxyResource.LookupAnsEntryByPartyResponse] = {
    implicit val AuthenticatedRequest(_, traceContext) = tUser
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
  )(
      name: String
  )(tUser: AuthenticatedRequest): Future[v0.ScanproxyResource.LookupAnsEntryByNameResponse] = {
    implicit val AuthenticatedRequest(_, traceContext) = tUser
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
      tUser: AuthenticatedRequest
  ): Future[v0.ScanproxyResource.ListAnsEntriesResponse] = {
    implicit val AuthenticatedRequest(_, traceContext) = tUser
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
  )(tUser: AuthenticatedRequest): Future[v0.ScanproxyResource.GetAnsRulesResponse] = {
    implicit val AuthenticatedRequest(_, traceContext) = tUser
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
      tUser: AuthenticatedRequest
  ): Future[ScanproxyResource.LookupTransferPreapprovalByPartyResponse] = {
    implicit val AuthenticatedRequest(_, traceContext) = tUser
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
      tUser: AuthenticatedRequest
  ): Future[ScanproxyResource.LookupTransferCommandCounterByPartyResponse] = {
    implicit val AuthenticatedRequest(_, traceContext) = tUser
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
      tUser: AuthenticatedRequest
  ): Future[ScanproxyResource.LookupTransferCommandStatusResponse] = {
    implicit val AuthenticatedRequest(_, traceContext) = tUser
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

  override def listUnclaimedDevelopmentFundCoupons(
      respond: ScanproxyResource.ListUnclaimedDevelopmentFundCouponsResponse.type
  )()(
      tUser: AuthenticatedRequest
  ): Future[ScanproxyResource.ListUnclaimedDevelopmentFundCouponsResponse] = {
    implicit val AuthenticatedRequest(_, traceContext) = tUser
    withSpan(s"$workflowId.listUnclaimedDevelopmentFundCoupons") { implicit traceContext => _ =>
      for {
        coupons <- scanConnection.listUnclaimedDevelopmentFundCoupons()
      } yield {
        respond.OK(
          definitions.ListUnclaimedDevelopmentFundCouponsResponse(
            coupons.map(_.toHttp).toVector
          )
        )
      }
    }
  }
}
