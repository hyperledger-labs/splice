package com.daml.network.sv.admin.http

import com.daml.network.codegen.java.cc
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.http.v0.{definitions, sv as v0}
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class HttpSvHandler(
    ledgerClient: CoinLedgerClient,
    svUserName: String,
    svStore: SvSvStore,
    svcStore: SvSvcStore,
    retryProvider: CoinRetries,
    flagCloseable: FlagCloseable,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.SvHandler
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val connection = ledgerClient.connection()
  private val svParty = svcStore.key.svParty
  private val svcParty = svcStore.key.svcParty

  def onboardValidator(
      respond: v0.SvResource.OnboardValidatorResponse.type
  )(body: definitions.OnboardValidatorRequest): Future[v0.SvResource.OnboardValidatorResponse] =
    // TODO(#2657) require secret
    withNewTrace(workflowId) { implicit traceContext => _ =>
      PartyId.fromProtoPrimitive(body.partyId) match {
        case Right(partyId) =>
          for {
            // We retry here because this mutates the CoinRules and rounds contracts,
            // which can lead to races.
            _ <- retryProvider.retryForAutomationGrpc(
              "onboard validator via SvcRules",
              onboardValidator(partyId),
              flagCloseable,
            )
          } yield v0.SvResource.OnboardValidatorResponseOK
        case Left(error) =>
          Future.successful(v0.SvResource.OnboardValidatorResponseBadRequest(error))
      }
    }

  def getDebugInfo(
      respond: v0.SvResource.GetDebugInfoResponse.type
  )(): Future[v0.SvResource.GetDebugInfoResponse] =
    withNewTrace(workflowId) { _ => _ =>
      for {
        coinRules <- svcStore.getCoinRules()
        svcRules <- svcStore.getSvcRules()
        validatorOnboardings <- svStore.listValidatorOnboardings()
      } yield definitions.GetDebugInfoResponse(
        svUser = svUserName,
        svPartyId = svParty.toProtoPrimitive,
        svcPartyId = svcParty.toProtoPrimitive,
        coinRulesContractId = coinRules.contractId.toString,
        svcRulesContractId = svcRules.contractId.toString,
        ongoingValidatorOnboardings = validatorOnboardings.length,
      )
    }

  def listConnectedDomains(
      respond: v0.SvResource.ListConnectedDomainsResponse.type
  )(): Future[v0.SvResource.ListConnectedDomainsResponse] = {
    withNewTrace(workflowId) { _ => span =>
      for {
        // both stores are typically (no hard guarantees) connected to the same domains
        domains <- svcStore.domains.listConnectedDomains()
      } yield v0.SvResource.ListConnectedDomainsResponse.OK(
        definitions.ListConnectedDomainsResponse(
          domains.view.map { case (k, v) =>
            k.toProtoPrimitive -> v.toProtoPrimitive
          }.toMap
        )
      )
    }
  }

  private def onboardValidator(
      candidateParty: PartyId
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      domainId <- svcStore.domains.getUniqueDomainId()
      openMiningRounds <- svcStore.acs.listContracts(cc.round.OpenMiningRound.COMPANION)
      issuingMiningRounds <- svcStore.acs.listContracts(cc.round.IssuingMiningRound.COMPANION)
      coinRules <- svcStore.getCoinRules()
      svcRules <- svcStore.getSvcRules()
      cmds = svcRules.contractId
        .exerciseSvcRules_OnboardValidator(
          svParty.toProtoPrimitive,
          candidateParty.toProtoPrimitive,
          coinRules.contractId,
          openMiningRounds.map(_.contractId).asJava,
          issuingMiningRounds.map(_.contractId).asJava,
        )
      // No command-dedup required, as the CoinRules contract is archived and recreated
      _ <- connection.submitWithResultNoDedup(
        Seq(svParty),
        Seq(svcParty),
        cmds,
        domainId,
      )
    } yield ()
}
