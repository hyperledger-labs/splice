package com.daml.network.validator.admin.http

import com.daml.network.auth.AuthExtractor.TracedUser
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

  override def getCoinRules(respond: v0.ScanproxyResource.GetCoinRulesResponse.type)()(
      tUser: TracedUser
  ): Future[v0.ScanproxyResource.GetCoinRulesResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.getCoinRules") { implicit traceContext => _ =>
      for {
        coinRules <- scanConnection.getCoinRulesWithState()
      } yield {
        respond.OK(definitions.GetCoinRulesProxyResponse(coinRules.toHttp))
      }
    }
  }

  override def lookupCnsEntryByParty(
      respond: v0.ScanproxyResource.LookupCnsEntryByPartyResponse.type
  )(
      party: String
  )(tUser: TracedUser): Future[v0.ScanproxyResource.LookupCnsEntryByPartyResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.lookupCnsEntryByParty") { implicit traceContext => _ =>
      for {
        entry <- scanConnection.lookupCnsEntryByParty(PartyId.tryFromProtoPrimitive(party))
      } yield {
        entry match {
          case Some(value) =>
            respond.OK(definitions.LookupEntryByPartyResponse(value.toHttp))
          case None =>
            respond.NotFound(definitions.ErrorResponse(s"Party $party does not have any CNSEntry."))
        }

      }
    }
  }

  override def lookupCnsEntryByName(
      respond: v0.ScanproxyResource.LookupCnsEntryByNameResponse.type
  )(name: String)(tUser: TracedUser): Future[v0.ScanproxyResource.LookupCnsEntryByNameResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.lookupCnsEntryByParty") { implicit traceContext => _ =>
      for {
        entry <- scanConnection.lookupCnsEntryByName(name)
      } yield {
        entry match {
          case Some(value) =>
            respond.OK(definitions.LookupEntryByNameResponse(value.toHttp))
          case None =>
            respond.NotFound(definitions.ErrorResponse(s"Did not find a CnsEntry with name $name"))
        }

      }
    }
  }

  override def listCnsEntries(
      respond: v0.ScanproxyResource.ListCnsEntriesResponse.type
  )(namePrefix: Option[String], pageSize: Int)(
      tUser: TracedUser
  ): Future[v0.ScanproxyResource.ListCnsEntriesResponse] = {
    implicit val TracedUser(_, traceContext) = tUser
    withSpan(s"$workflowId.lookupCnsEntryByParty") { implicit traceContext => _ =>
      for {
        entries <- scanConnection.listCnsEntries(namePrefix, pageSize)
      } yield {
        respond.OK(definitions.ListEntriesResponse(entries.map(_.toHttp).toVector))
      }
    }
  }
}
