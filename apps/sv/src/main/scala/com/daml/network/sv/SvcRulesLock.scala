package com.daml.network.sv

import com.daml.network.environment.RetryProvider
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.logging.{NamedLogging, NamedLoggerFactory}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.ExecutionContext

class SvcRulesLock(
    globalDomain: DomainId,
    svcStoreWithIngestion: CNNodeAppStoreWithIngestion[SvSvcStore],
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {
  private val svcStore = svcStoreWithIngestion.store
  private val svParty = svcStore.key.svParty
  private val svcParty = svcStore.key.svcParty

  def lock()(implicit tc: TraceContext) =
    retryProvider.retryForClientCalls(
      "locking SvcRules and CoinRules contracts",
      for {
        svcRules <- svcStore.getSvcRules()
        coinRules <- svcStore.getCoinRules()
        res <- svcStoreWithIngestion.connection.submitWithResultNoDedup(
          Seq(svParty),
          Seq(svcParty),
          svcRules.contractId.exerciseSvcRules_Lock(svParty.toProtoPrimitive, coinRules.contractId),
          globalDomain,
        )
      } yield res,
      logger,
    )

  def unlock()(implicit tc: TraceContext) =
    retryProvider.retryForClientCalls(
      "unlocking SvcRules and CoinRules contracts",
      for {
        svcRules <- svcStore.getSvcRules()
        coinRules <- svcStore.getCoinRules()
        res <- svcStoreWithIngestion.connection.submitWithResultNoDedup(
          Seq(svParty),
          Seq(svcParty),
          svcRules.contractId
            .exerciseSvcRules_Unlock(svParty.toProtoPrimitive, coinRules.contractId),
          globalDomain,
        ),
      } yield res,
      logger,
    )
}
