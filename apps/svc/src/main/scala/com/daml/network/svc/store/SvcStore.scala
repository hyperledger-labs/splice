package com.daml.network.svc.store

import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.store.{AcsStore, CoinAppStoreWithoutHistory}
import com.daml.network.svc.config.SvcDomainConfig
import com.daml.network.svc.store.memory.InMemorySvcStore
import com.daml.network.util.{CoinUtil, Contract}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import com.daml.network.codegen.java.cc.coin.UnclaimedReward
import com.daml.network.environment.CoinRetries

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait SvcStore extends CoinAppStoreWithoutHistory {

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

  def lookupValidatorRightByPartyWithOffset(
      party: PartyId
  ): Future[
    QueryResult[Option[Contract[cc.coin.ValidatorRight.ContractId, cc.coin.ValidatorRight]]]
  ] =
    defaultAcs.flatMap(
      _.findContractWithOffset(cc.coin.ValidatorRight.COMPANION)(co =>
        co.payload.user == party.toProtoPrimitive
      )
    )

  /** Lookup the triple of open mining rounds that should always be present after boostrapping. */
  def lookupOpenMiningRoundTriple()(implicit
      ec: ExecutionContext
  ): Future[Option[SvcStore.OpenMiningRoundTriple]] =
    for {
      acs <- defaultAcs
      openMiningRounds <- acs.listContracts(cc.round.OpenMiningRound.COMPANION)
      result = openMiningRounds.sortBy(contract => contract.payload.round.number) match {
        case Seq(oldest, middle, newest) =>
          Some(SvcStore.OpenMiningRoundTriple(oldest = oldest, middle = middle, newest = newest))
        case _ => None
      }
    } yield result

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

  def listUnclaimedRewards(
      limit: Long
  ): Future[Seq[Contract[UnclaimedReward.ContractId, cc.coin.UnclaimedReward]]] =
    defaultAcs.flatMap(
      _.listContracts(
        cc.coin.UnclaimedReward.COMPANION,
        (_: Contract[cc.coin.UnclaimedReward.ContractId, cc.coin.UnclaimedReward]) => true,
        Some(limit),
      )
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

  def listAppRewardCouponsGroupedByCounterparty(
      round: Long,
      totalCouponsLimit: Option[Long],
  ): Future[Seq[Seq[cc.coin.AppRewardCoupon.ContractId]]] = {
    for {
      appRewards <- listAppRewardCoupons(round, totalCouponsLimit)
      providerToCoupons = appRewards.foldLeft(
        Map[String, Seq[cc.coin.AppRewardCoupon.ContractId]]()
      ) { (m, r) =>
        m +
          (r.payload.provider -> (Seq(r.contractId) ++ m.getOrElse(
            r.payload.provider,
            Seq[cc.coin.AppRewardCoupon.ContractId](),
          )))
      }
      appRewardCouponsGrouped = providerToCoupons.toSeq.map { case (_, coupons) => coupons }
    } yield appRewardCouponsGrouped
  }

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

  def listValidatorRewardCouponsGroupedByCounterparty(
      round: Long,
      totalCouponsLimit: Option[Long],
  ): Future[Seq[Seq[cc.coin.ValidatorRewardCoupon.ContractId]]] = {
    for {
      validatorRewards <- listValidatorRewardCoupons(round, totalCouponsLimit)
      validatorToCoupons = validatorRewards.foldLeft(
        Map[String, Seq[cc.coin.ValidatorRewardCoupon.ContractId]]()
      ) { (m, r) =>
        m +
          (r.payload.user -> (Seq(r.contractId) ++ m.getOrElse(
            r.payload.user,
            Seq[cc.coin.ValidatorRewardCoupon.ContractId](),
          )))
      }
      validatorRewardCouponsGrouped = validatorToCoupons.toSeq.map { case (_, coupons) => coupons }
    } yield validatorRewardCouponsGrouped
  }

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
      retryProvider: CoinRetries,
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
        mkFilter(cc.coin.UnclaimedReward.COMPANION)(co => co.payload.svc == svc),
      ),
    )
  }

  type OpenMiningRoundContract =
    Contract[cc.round.OpenMiningRound.ContractId, cc.round.OpenMiningRound]

  case class OpenMiningRoundTriple(
      oldest: OpenMiningRoundContract,
      middle: OpenMiningRoundContract,
      newest: OpenMiningRoundContract,
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(param("oldest", _.oldest), param("middle", _.middle), param("newest", _.newest))

    /** The time after which these can be advanced at assuming the given tick duration. */
    def readyToAdvanceAt: Instant = {
      val middleTickDuration = CoinUtil.relTimeToDuration(
        middle.payload.tickDuration
      )
      Ordering[Instant].max(
        oldest.payload.targetClosesAt,
        Ordering[Instant].max(
          // TODO(M3-07): when changing CoinConfigs it will make sense to store tickDuration on the rounds and express targetClosesAt as 2 * tickDuration
          middle.payload.opensAt.plus(middleTickDuration),
          newest.payload.opensAt,
        ),
      )
    }

    def toSeq: Seq[OpenMiningRoundContract] = Seq(oldest, middle, newest)
  }
}
