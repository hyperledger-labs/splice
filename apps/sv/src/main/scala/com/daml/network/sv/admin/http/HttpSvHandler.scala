package com.daml.network.sv.admin.http

import com.daml.network.environment.CoinLedgerClient
import com.daml.network.http.v0.{definitions, sv as v0}
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class HttpSvHandler(
    ledgerClient: CoinLedgerClient,
    svUserName: String,
    svStore: SvSvStore,
    svcStore: SvSvcStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.SvHandler
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName

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
        svPartyId = svcStore.key.svParty.toProtoPrimitive,
        svcPartyId = svcStore.key.svcParty.toProtoPrimitive,
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
}
