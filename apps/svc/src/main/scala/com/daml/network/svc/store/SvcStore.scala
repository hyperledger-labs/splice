package com.daml.network.svc.store

import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.store.{AcsStore, CNNodeAppStoreWithoutHistory}
import com.daml.network.svc.config.SvcDomainConfig
import com.daml.network.svc.store.memory.InMemorySvcStore
import com.daml.network.util.Contract
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future}
import com.daml.network.environment.RetryProvider

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait SvcStore extends CNNodeAppStoreWithoutHistory {

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  protected[this] def domainConfig: SvcDomainConfig

  override final def defaultAcsDomain = domainConfig.global

  def lookupCoinRulesWithOffset(): Future[
    QueryResult[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]]
  ] =
    defaultAcs.flatMap(_.findContractWithOffset(cc.coin.CoinRules.COMPANION)(_ => true))

  def lookupCoinRules(): Future[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]] =
    lookupCoinRulesWithOffset().map(_.value)

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
  def lookupSvcRulesWithOffset(
  ): Future[
    QueryResult[Option[Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]]]
  ] =
    defaultAcs.flatMap(_.findContractWithOffset(cn.svcrules.SvcRules.COMPANION)(_ => true))

  // TODO(#2241) move to SV app once ready to move away from mock SVC bootstrap
  def lookupSvcRules()(implicit
      ec: ExecutionContext
  ): Future[Option[Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]]] =
    lookupSvcRulesWithOffset().map(_.value)

  import com.daml.network.automation.ExpiredContractTrigger.ListExpiredContracts
  import AcsStore.listExpiredFromPayloadExpiry

  /** List issuing mining rounds past their targetClosesAt */
  def listExpiredIssuingMiningRounds
      : ListExpiredContracts[cc.round.IssuingMiningRound.ContractId, cc.round.IssuingMiningRound] =
    listExpiredFromPayloadExpiry(defaultAcs, cc.round.IssuingMiningRound.COMPANION)(
      _.targetClosesAt
    )

  def lookupFeaturedAppByProviderWithOffset(
      provider: String
  ): Future[QueryResult[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]]] =
    defaultAcs.flatMap(
      _.findContractWithOffset(FeaturedAppRight.COMPANION)(co => co.payload.provider == provider)
    )

  def lookupOldestClosedMiningRound(): Future[
    Option[Contract[cc.round.ClosedMiningRound.ContractId, cc.round.ClosedMiningRound]]
  ] =
    for {
      acs <- defaultAcs
      rounds <- acs.listContracts(cc.round.ClosedMiningRound.COMPANION)
    } yield rounds.sortBy(_.payload.round.number).headOption

  def listAppRewardCoupons(
      round: Long,
      limit: Option[Long] = None,
  ): Future[Seq[Contract[cc.coin.AppRewardCoupon.ContractId, cc.coin.AppRewardCoupon]]] =
    defaultAcs.flatMap(
      _.listContracts(
        cc.coin.AppRewardCoupon.COMPANION,
        (co: Contract[cc.coin.AppRewardCoupon.ContractId, cc.coin.AppRewardCoupon]) =>
          co.payload.round.number == round,
        limit,
      )
    )

  def listValidatorRewardCoupons(
      round: Long,
      limit: Option[Long] = None,
  ): Future[
    Seq[Contract[cc.coin.ValidatorRewardCoupon.ContractId, cc.coin.ValidatorRewardCoupon]]
  ] =
    defaultAcs.flatMap(
      _.listContracts(
        cc.coin.ValidatorRewardCoupon.COMPANION,
        (co: Contract[cc.coin.ValidatorRewardCoupon.ContractId, cc.coin.ValidatorRewardCoupon]) =>
          co.payload.round.number == round,
        limit,
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
