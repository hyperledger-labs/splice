package com.daml.network.svc.store

import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{
  CNNodeAppStoreWithoutHistory,
  InMemoryMultiDomainAcsStore,
  MultiDomainAcsStore,
  ConfiguredDefaultDomain,
  TxLogStore,
}
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.svc.config.SvcDomainConfig
import com.daml.network.svc.store.memory.InMemorySvcStore
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future}

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait SvcStore extends CNNodeAppStoreWithoutHistory with ConfiguredDefaultDomain {

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  protected[this] def domainConfig: SvcDomainConfig

  override final def defaultAcsDomain = domainConfig.global.alias

  override def multiDomainAcsStore: InMemoryMultiDomainAcsStore[
    TxLogStore.IndexRecord,
    TxLogStore.Entry[TxLogStore.IndexRecord],
  ]

  def lookupCoinRules()(implicit
      tc: TraceContext
  ): Future[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomain(cc.coin.CoinRules.COMPANION)(_, _ => true)
    )

  // needed for round automation
  def getCoinRules()(implicit
      tc: TraceContext
  ): Future[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]] =
    lookupCoinRules().map(
      _.getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("No active CoinRules contract")
        )
      )
    )

  def lookupSvcRules()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomain(cn.svcrules.SvcRules.COMPANION)(_, (_: Any) => true)
    )

  def lookupFeaturedAppByProviderWithOffset(
      provider: String
  )(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(FeaturedAppRight.COMPANION)(
        _,
        co => co.payload.provider == provider,
      )
    )
}

object SvcStore {

  def apply(
      svcParty: PartyId,
      storage: Storage,
      domains: SvcDomainConfig,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
  )(implicit
      ec: ExecutionContext
  ): SvcStore =
    storage match {
      case _: MemoryStorage =>
        new InMemorySvcStore(
          svcParty = svcParty,
          domains,
          loggerFactory,
          retryProvider,
        )
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  /** Contract filter of an svc acs store for a specific acs party. */
  def contractFilter(svcParty: PartyId): MultiDomainAcsStore.ContractFilter = {
    import MultiDomainAcsStore.mkFilter
    val svc = svcParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      svcParty,
      Map(
        mkFilter(cc.coin.CoinRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.Coin.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.LockedCoin.COMPANION)(co => co.payload.coin.svc == svc),
        mkFilter(cc.coin.ValidatorRight.COMPANION)(co =>
          co.payload.svc == svc && co.payload.validator == svc && co.payload.user == svc
        ),
        mkFilter(cc.round.OpenMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.ClosedMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.IssuingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.SummarizingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.AppRewardCoupon.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.ValidatorRewardCoupon.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.FeaturedAppRight.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cn.svcrules.SvcRules.COMPANION)(co => co.payload.svc == svc),
      ),
    )
  }
}
