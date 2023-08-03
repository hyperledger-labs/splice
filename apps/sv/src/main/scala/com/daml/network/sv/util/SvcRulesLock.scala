package com.daml.network.sv.util

import com.daml.network.environment.RetryProvider
import com.daml.network.store.CNNodeAppStoreWithIngestion
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

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
    retryProvider.retryForAutomation(
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
    retryProvider.retryForAutomation(
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

  def withLock[T](reason: String)(f: => Future[T])(implicit tc: TraceContext): Future[T] = {
    logger.info(s"locked SvcRules and CoinRules contracts before $reason")
    lock().flatMap(_ =>
      f.andThen(_ => {
        unlock().map(_ => logger.info(s"unlocked SvcRules and CoinRules contracts after $reason"))
      })
    )
  }
}
