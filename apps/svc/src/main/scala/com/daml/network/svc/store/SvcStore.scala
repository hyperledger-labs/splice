package com.daml.network.svc.store

import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.store.{AcsStore, CNNodeAppStoreWithoutHistory}
import com.daml.network.svc.config.SvcDomainConfig
import com.daml.network.svc.store.memory.InMemorySvcStore
import com.daml.network.util.Contract
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future}
import com.daml.network.environment.RetryProvider

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait SvcStore extends CNNodeAppStoreWithoutHistory {

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  override final def acs(domain: DomainId): Future[AcsStore] =
    Future.failed(
      new RuntimeException(
        "SvcStore has been migrated to new ACS store, use `multiDomainAcsStore` instead"
      )
    )

  private def defaultAcsDomainIdF = domains.signalWhenConnected(defaultAcsDomain)

  protected[this] def domainConfig: SvcDomainConfig

  override final def defaultAcsDomain = domainConfig.global

  def lookupCoinRules(): Future[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomain(cc.coin.CoinRules.COMPANION)(_, _ => true)
    )

  // needed for round automation
  def getCoinRules(): Future[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]] =
    lookupCoinRules().map(
      _.getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("No active CoinRules contract")
        )
      )
    )

  // TODO(#2241) move to SV app once ready to move away from mock SVC bootstrap
  def lookupSvcRules()(implicit
      ec: ExecutionContext
  ): Future[Option[Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomain(cn.svcrules.SvcRules.COMPANION)(_, (_: Any) => true)
    )

  import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts

  /** List issuing mining rounds past their targetClosesAt */
  def listExpiredIssuingMiningRounds
      : ListExpiredContracts[cc.round.IssuingMiningRound.ContractId, cc.round.IssuingMiningRound] =
    multiDomainAcsStore.listExpiredFromPayloadExpiry(cc.round.IssuingMiningRound.COMPANION)(
      _.targetClosesAt
    )

  def lookupFeaturedAppByProviderWithOffset(
      provider: String
  ): Future[QueryResult[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(FeaturedAppRight.COMPANION)(
        _,
        co => co.payload.provider == provider,
      )
    )
}

object SvcStore {

  case class RoundTotals(
      transferFees: BigDecimal = 0.0,
      adminFees: BigDecimal = 0.0,
      holdingFees: BigDecimal = 0.0,
      transferInputs: BigDecimal = 0.0,
      nonSelfTransferOutputs: BigDecimal = 0.0,
      selfTransferOutputs: BigDecimal = 0.0,
  )

  def apply(
      svcParty: PartyId,
      storage: Storage,
      domains: SvcDomainConfig,
      loggerFactory: NamedLoggerFactory,
      futureSupervisor: FutureSupervisor,
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
          futureSupervisor,
          retryProvider,
        )
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  /** Contract filter of an svc acs store for a specific acs party. */
  def contractFilter(svcParty: PartyId): AcsStore.ContractFilter = {
    import AcsStore.mkFilter
    val svc = svcParty.toProtoPrimitive

    AcsStore.SimpleContractFilter(
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
