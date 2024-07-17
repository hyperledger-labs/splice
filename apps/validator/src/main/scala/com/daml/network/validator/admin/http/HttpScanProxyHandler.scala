// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.admin.http

import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.http.v0.definitions.MaybeCachedContractWithState
import com.daml.network.http.v0.{definitions, scanproxy as v0}
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

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
      body: com.daml.network.http.v0.definitions.GetAnsRulesRequest
  )(tUser: TracedUser): Future[v0.ScanproxyResource.GetAnsRulesResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.getAnsRules") { implicit traceContext => _ =>
      for {
        response <- scanConnection.getAnsRules()
        maybeDomainId = response.state.fold(dId => Some(dId.toProtoPrimitive), None)
        maybeCachedContract = MaybeCachedContractWithState(
          Some(response.contract.toHttp),
          maybeDomainId,
        )
      } yield {
        respond.OK(definitions.GetAnsRulesResponse(ansRulesUpdate = maybeCachedContract))
      }
    }
  }
}
