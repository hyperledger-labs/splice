package com.daml.network.store.memory

import com.daml.network.codegen.java.cc.coin.AppRewardCoupon
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{
  HardLimit,
  InMemoryMultiDomainAcsStore,
  MultiDomainAcsStore,
  MultiDomainAcsStoreTest,
}
import com.daml.network.store.StoreTest.{TestTxLogEntry, testTxLogConfig}
import com.daml.network.util.Contract
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory.NoOpMetricsFactory

class InMemoryMultiDomainAcsStoreTest
    extends MultiDomainAcsStoreTest[
      InMemoryMultiDomainAcsStore[TestTxLogEntry]
    ]
    with HasExecutionContext
    with NamedLogging
    with HasActorSystem {
  import MultiDomainAcsStore.*

  override def mkStore(
      id: Int,
      filter: MultiDomainAcsStore.ContractFilter[GenericAcsRowData],
  ): InMemoryMultiDomainAcsStore[TestTxLogEntry] =
    new InMemoryMultiDomainAcsStore(
      loggerFactory,
      filter,
      testTxLogConfig,
      RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    )(actorSystem.dispatcher)

  "filter before limit" in {
    implicit val store = mkStore()
    for {
      _ <- acs()
      _ <- d1.create(c(1))
      _ <- d1.create(c(2))
      round1 <- store.filterContracts(
        AppRewardCoupon.COMPANION,
        limit = HardLimit.tryCreate(1),
        filter =
          (c: Contract[AppRewardCoupon.ContractId, AppRewardCoupon]) => c.payload.round.number == 1,
      )
      round2 <- store.filterContracts(
        AppRewardCoupon.COMPANION,
        limit = HardLimit.tryCreate(1),
        filter =
          (c: Contract[AppRewardCoupon.ContractId, AppRewardCoupon]) => c.payload.round.number == 2,
      )
    } yield {
      round1.map(_.contract) shouldBe Seq(c(1))
      round2.map(_.contract) shouldBe Seq(c(2))
    }
  }
}
