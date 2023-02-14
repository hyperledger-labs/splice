package com.daml.network.scan.admin.http

import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.http.v0.{definitions, scan as v0}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer
import com.daml.network.scan.store.ScanStore
import com.digitalasset.canton.time.Clock
import com.daml.network.codegen.java.cc.round as roundCodegen
import com.daml.network.codegen.java.cc.round.IssuingMiningRound
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.http.v0.definitions.MaybeCachedContract

import scala.concurrent.{ExecutionContext, Future}

class HttpScanHandler(
    ledgerClient: CoinLedgerClient,
    store: ScanStore,
    clock: Clock,
    retryProvider: CoinRetries,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.ScanHandler
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName

  def getSvcPartyId(
      response: v0.ScanResource.GetSvcPartyIdResponse.type
  )(): Future[v0.ScanResource.GetSvcPartyIdResponse] =
    withNewTrace(workflowId) { _ => span =>
      Future.successful(definitions.GetSvcPartyIdResponse(store.svcParty.toProtoPrimitive))
    }

  def getLatestOpenAndIssuingMiningRounds(
      response: v0.ScanResource.GetLatestOpenAndIssuingMiningRoundsResponse.type
  )(
      body: com.daml.network.http.v0.definitions.GetLatestOpenAndIssuingMiningRoundsRequest
  ): Future[v0.ScanResource.GetLatestOpenAndIssuingMiningRoundsResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      val now = clock.now
      for {
        latestOpen <- store.getLatestOpenMiningRound(now)
        acs <- store.defaultAcs
        issuingRounds <- acs.listContracts(IssuingMiningRound.COMPANION)
        issuingRoundsCachedByClient = body.cachedIssuingRoundContractIds.toSet
        issuingRoundsResponseMap = issuingRounds
          .map(round => {
            val roundIsAlreadyCached =
              issuingRoundsCachedByClient.contains(round.contractId.contractId)
            (
              round.contractId.contractId,
              if (roundIsAlreadyCached) {
                logger.debug(
                  s"Not sending issuing mining round ${round.payload.round.number} again because it is already cached by the client."
                )
                MaybeCachedContract(None)
              } else MaybeCachedContract(Some(round.toJson)),
            )
          })
          .toMap
        omrResponse = body.cachedOpenMiningRoundContractId match {
          case Some(contractId) if contractId == latestOpen.contractId.contractId =>
            MaybeCachedContract(Some(latestOpen.toJson))
          case _ =>
            MaybeCachedContract(Some(latestOpen.toJson))
        }
      } yield {
        definitions.GetLatestOpenAndIssuingMiningRoundsResponse(
          latestOpenMiningRound = omrResponse,
          issuingMiningRounds = issuingRoundsResponseMap,
        )
      }
    }

  def getCoinRules(
      response: v0.ScanResource.GetCoinRulesResponse.type
  )(
      body: com.daml.network.http.v0.definitions.GetCoinRulesRequest
  ): Future[v0.ScanResource.GetCoinRulesResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      for {
        coinRulesO <- store.lookupCoinRules()
        coinRules = coinRulesO.getOrElse(
          throw new NoSuchElementException("found no coinrules instance")
        )
      } yield {
        val response = body.cachedCoinRulesContractId match {
          case Some(cachedContractId) if cachedContractId == coinRules.contractId.contractId =>
            logger.debug(
              s"Not sending coin rules with contract-id $cachedContractId again because they are already cached by the client."
            )
            MaybeCachedContract(None)
          case Some(_) => // else: coin rules are cached but outdated.
            MaybeCachedContract(Some(coinRules.toJson))
          case None =>
            MaybeCachedContract(Some(coinRules.toJson))
        }
        definitions.GetCoinRulesResponse(
          coinRulesUpdate = response
        )
      }
    }

  def getClosedRounds(
      response: v0.ScanResource.GetClosedRoundsResponse.type
  )(): Future[v0.ScanResource.GetClosedRoundsResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      for {
        acs <- store.defaultAcs
        rounds <- acs.listContracts(roundCodegen.ClosedMiningRound.COMPANION)
      } yield {
        val filteredRounds = rounds.sortWith(_.payload.round.number > _.payload.round.number)
        definitions.GetClosedRoundsResponse(filteredRounds.toVector.map(r => r.toJson))
      }
    }

  def getTransferContext(
      response: v0.ScanResource.GetTransferContextResponse.type
  )(): Future[v0.ScanResource.GetTransferContextResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      for {
        coinRules <- store.lookupCoinRules()
        now = clock.now
        latestOpen <- store.getLatestOpenMiningRound(now)
        rounds <- store.lookupSubmittableOpenMiningRounds(now)
      } yield {
        definitions.GetTransferContextResponse(
          coinRules.map(_.toJson),
          latestOpen.toJson,
          // TODO(M3-09): consider just removing this attribute - not used except in tests.
          rounds.toVector.map(_.toJson),
        )
      }
    }

  def listFeaturedAppRights(
      response: v0.ScanResource.ListFeaturedAppRightsResponse.type
  )(): Future[v0.ScanResource.ListFeaturedAppRightsResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      for {
        acs <- store.defaultAcs
        apps <- acs.listContracts(FeaturedAppRight.COMPANION)
      } yield {
        definitions.ListFeaturedAppRightsResponse(apps.toVector.map(a => a.toJson))
      }
    }

  def lookupFeaturedAppRight(
      response: com.daml.network.http.v0.scan.ScanResource.LookupFeaturedAppRightResponse.type
  )(providerPartyId: String): Future[
    com.daml.network.http.v0.scan.ScanResource.LookupFeaturedAppRightResponse
  ] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      for {
        acs <- store.defaultAcs
        right <- acs.findContract(FeaturedAppRight.COMPANION)(co =>
          co.payload.provider == providerPartyId
        )
      } yield {
        definitions.LookupFeaturedAppRightResponse(right.map(r => r.toJson))
      }
    }

  def listConnectedDomains(
      response: com.daml.network.http.v0.scan.ScanResource.ListConnectedDomainsResponse.type
  )(): Future[com.daml.network.http.v0.scan.ScanResource.ListConnectedDomainsResponse] = {
    withNewTrace(workflowId) { _ => span =>
      for {
        domains <- store.domains.listConnectedDomains()
      } yield v0.ScanResource.ListConnectedDomainsResponse.OK(
        definitions.ListConnectedDomainsResponse(
          domains.view.map { case (k, v) =>
            k.toProtoPrimitive -> v.toProtoPrimitive
          }.toMap
        )
      )
    }
  }
}
