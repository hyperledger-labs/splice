package com.daml.network.sv.store

import cats.syntax.traverse.*
import com.daml.ledger.javaapi.data as javab
import com.daml.network.automation.ExpiredContractTrigger.ListExpiredContracts
import com.daml.network.codegen.java.cc.coin.CoinRules_MiningRound_Archive
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.codegen.java.cn.svcrules.ActionRequiringConfirmation
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_CoinRules
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.CRARC_MiningRound_Archive
import com.daml.network.codegen.java.cn.svonboarding as so
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{AcsStore, CNNodeAppStoreWithoutHistory}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.sv.config.SvDomainConfig
import com.daml.network.sv.store.memory.InMemorySvSvcStore
import com.daml.network.util.{CNNodeUtil, Contract}
import com.daml.network.util.Contract.Companion.Template as TemplateCompanion
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

/* Store used by the SV app for filtering contracts visible to the SVC party. */
trait SvSvcStore extends CNNodeAppStoreWithoutHistory {

  def key: SvStore.Key

  protected[this] def domainConfig: SvDomainConfig

  override final def defaultAcsDomain = domainConfig.global

  def lookupSvcRulesWithOffset(
  ): Future[
    QueryResult[Option[Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]]]
  ] =
    defaultAcs.flatMap(_.findContractWithOffset(cn.svcrules.SvcRules.COMPANION)(_ => true))

  def lookupSvcRules()
      : Future[Option[Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]]] =
    lookupSvcRulesWithOffset().map(_.value)

  def getSvcRules(): Future[Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]] =
    lookupSvcRules().map(
      _.getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("No active SvcRules contract")
        )
      )
    )

  def svIsLeader(): Future[Boolean] =
    getSvcRules().map(_.payload.leader == key.svParty.toProtoPrimitive)

  def lookupCoinRulesWithOffset(
  ): Future[
    QueryResult[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]]
  ] =
    defaultAcs.flatMap(_.findContractWithOffset(cc.coin.CoinRules.COMPANION)(_ => true))

  def lookupCoinRules(): Future[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]] =
    lookupCoinRulesWithOffset().map(_.value)

  def getCoinRules(): Future[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]] =
    lookupCoinRules().map(
      _.getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("No active CoinRules contract")
        )
      )
    )

  def lookupAgreedCoinPriceWithOffset(
  ): Future[
    QueryResult[
      Option[Contract[cn.svcrules.AgreedCoinPrice.ContractId, cn.svcrules.AgreedCoinPrice]]
    ]
  ] =
    defaultAcs.flatMap(_.findContractWithOffset(cn.svcrules.AgreedCoinPrice.COMPANION)(_ => true))

  def lookupAgreedCoinPrice(): Future[
    Option[Contract[cn.svcrules.AgreedCoinPrice.ContractId, cn.svcrules.AgreedCoinPrice]]
  ] =
    lookupAgreedCoinPriceWithOffset().map(_.value)

  def getAgreedCoinPrice()
      : Future[Contract[cn.svcrules.AgreedCoinPrice.ContractId, cn.svcrules.AgreedCoinPrice]] =
    lookupAgreedCoinPrice().map(
      _.getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("No active agreed coin price")
        )
      )
    )

  /** Lookup the triple of open mining rounds that should always be present after boostrapping. */
  def lookupOpenMiningRoundTriple()(implicit
      ec: ExecutionContext
  ): Future[Option[SvSvcStore.OpenMiningRoundTriple]] =
    for {
      acs <- defaultAcs
      openMiningRounds <- acs.listContracts(cc.round.OpenMiningRound.COMPANION)
      result = openMiningRounds.sortBy(contract => contract.payload.round.number) match {
        case Seq(oldest, middle, newest) =>
          Some(SvSvcStore.OpenMiningRoundTriple(oldest = oldest, middle = middle, newest = newest))
        case _ => None
      }
    } yield result

  def lookupLatestActiveOpenMiningRound()(implicit
      ec: ExecutionContext
  ): Future[Option[SvSvcStore.OpenMiningRoundContract]] =
    lookupOpenMiningRoundTriple().map(_.map(_.newest))

  /** get the latest active open mining round contract, which should always be present after bootstrapping. */
  def getLatestActiveOpenMiningRound()(implicit
      ec: ExecutionContext
  ): Future[SvSvcStore.OpenMiningRoundContract] = lookupLatestActiveOpenMiningRound().map(
    _.getOrElse(
      throw new StatusRuntimeException(
        Status.NOT_FOUND.withDescription("No active OpenMiningRound contract")
      )
    )
  )

  /** List coins that are expired and can never be used as transfer input. */
  def listExpiredCoins: ListExpiredContracts[cc.coin.Coin.ContractId, cc.coin.Coin] =
    listExpiredRoundBased(cc.coin.Coin.COMPANION)(identity)

  /** List locked coins that are expired and can never be used as transfer input. */
  def listLockedExpiredCoins
      : ListExpiredContracts[cc.coin.LockedCoin.ContractId, cc.coin.LockedCoin] =
    listExpiredRoundBased(cc.coin.LockedCoin.COMPANION)(_.coin)

  def listConfirmations(
      action: cn.svcrules.ActionRequiringConfirmation
  ): Future[Seq[Contract[cn.svcrules.Confirmation.ContractId, cn.svcrules.Confirmation]]] = {
    for {
      acs <- defaultAcs
      confirmations <- acs.listContracts(cn.svcrules.Confirmation.COMPANION)
    } yield confirmations.filter(_.payload.action.toValue == action.toValue)
  }

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

  def lookupOldestClosedMiningRound(): Future[
    Option[Contract[cc.round.ClosedMiningRound.ContractId, cc.round.ClosedMiningRound]]
  ] =
    for {
      acs <- defaultAcs
      rounds <- acs.listContracts(cc.round.ClosedMiningRound.COMPANION)
    } yield rounds.sortBy(_.payload.round.number).headOption

  /** All `ClosedMiningRound` contracts that should be confirmed to be archived.
    *
    * These are all `ClosedMiningRound` contracts for which
    * 1. there are no left-over reward coupon contracts and
    * 2. there does not yet exist a ready-to-be-archived confirmation by this SV.
    *
    * Note: The QueryResult in the return value is composed of the closed mining round contract
    * and the offset from the query for the confirmation contract.
    */
  def listArchivableClosedMiningRounds(): Future[
    Seq[QueryResult[Contract[cc.round.ClosedMiningRound.ContractId, cc.round.ClosedMiningRound]]]
  ] = {
    for {
      acs <- defaultAcs
      closedRounds <- acs.listContracts(cc.round.ClosedMiningRound.COMPANION)
      archivableClosedRounds <- closedRounds.traverse(round => {
        for {
          appRewardCoupons <- listAppRewardCoupons(round.payload.round.number, Some(1))
          validatorRewardCoupons <- listValidatorRewardCoupons(
            round.payload.round.number,
            Some(1),
          )
          coinRules <- getCoinRules()
          action = new ARC_CoinRules(
            coinRules.contractId,
            new CRARC_MiningRound_Archive(
              new CoinRules_MiningRound_Archive(
                round.contractId
              )
            ),
          )
          confirmationQueryResult <- lookupConfirmationByActionWithOffset(key.svParty, action)
        } yield {
          (
            // archivable if ...
            if (
              // ... there are no unclaimed rewards left in this round
              appRewardCoupons.isEmpty && validatorRewardCoupons.isEmpty &&
              // ... and a confirmation to archive is not already created by this SV
              confirmationQueryResult.value.isEmpty
            ) Some(QueryResult(confirmationQueryResult.offset, round))
            else None
          )
        }
      })
    } yield archivableClosedRounds.flatten
  }

  def lookupConfirmationByActionWithOffset(
      confirmer: PartyId,
      action: ActionRequiringConfirmation,
  ): Future[
    QueryResult[Option[Contract[cn.svcrules.Confirmation.ContractId, cn.svcrules.Confirmation]]]
  ] = {
    defaultAcs.flatMap(
      _.findContractWithOffset(cn.svcrules.Confirmation.COMPANION)(co =>
        co.payload.confirmer == confirmer.toProtoPrimitive && co.payload.action.toValue == action.toValue
      )
    )
  }

  def lookupSvOnboardingByTokenWithOffset(
      token: String
  ): Future[
    QueryResult[Option[Contract[so.SvOnboarding.ContractId, so.SvOnboarding]]]
  ] =
    defaultAcs.flatMap(
      _.findContractWithOffset(so.SvOnboarding.COMPANION)(co => co.payload.token == token)
    )

  def listExpiredSvOnboardings: ListExpiredContracts[so.SvOnboarding.ContractId, so.SvOnboarding] =
    AcsStore.listExpiredFromPayloadExpiry(defaultAcs, so.SvOnboarding.COMPANION)(
      _.expiresAt
    )

  private[this] def listExpiredRoundBased[Id <: javab.codegen.ContractId[T], T <: javab.Template](
      companion: TemplateCompanion[Id, T]
  )(coin: T => cc.coin.Coin): ListExpiredContracts[Id, T] = (_, limit) =>
    for {
      maybeLatestOpenMiningRound <- lookupLatestActiveOpenMiningRound()
      result <- maybeLatestOpenMiningRound.fold(
        Future.successful(Seq.empty[Contract[Id, T]])
      ) { latest =>
        for {
          acs <- defaultAcs
          allExpired <- acs
            .listContracts(
              companion,
              (e: Contract[Id, T]) =>
                CNNodeUtil.coinExpiresAt(coin(e.payload)).number <= latest.payload.round.number - 2,
            )
        } yield allExpired.iterator.take(limit).toSeq
      }
    } yield result
}

object SvSvcStore {
  def apply(
      key: SvStore.Key,
      storage: Storage,
      domains: SvDomainConfig,
      loggerFactory: NamedLoggerFactory,
      futureSupervisor: FutureSupervisor,
      retryProvider: RetryProvider,
  )(implicit ec: ExecutionContext): SvSvcStore =
    storage match {
      case _: MemoryStorage =>
        new InMemorySvSvcStore(key, domains, loggerFactory, futureSupervisor, retryProvider)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  /** Contract filter of an sv acs store for a specific acs party. */
  def contractFilter(svcParty: PartyId, svParty: PartyId): AcsStore.ContractFilter = {
    import AcsStore.mkFilter
    val svc = svcParty.toProtoPrimitive
    val sv = svParty.toProtoPrimitive

    AcsStore.SimpleContractFilter(
      svcParty,
      Map(
        mkFilter(cn.svcrules.AgreedCoinPrice.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cn.svcrules.Confirmation.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cn.svcrules.SvcRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cn.svcrules.SvReward.COMPANION)(co =>
          co.payload.svc == svc && co.payload.sv == sv
        ),
        mkFilter(so.SvOnboarding.COMPANION)(co => co.payload.svc == svc),
        mkFilter(so.SvConfirmed.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.CoinRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.Coin.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.LockedCoin.COMPANION)(co => co.payload.coin.svc == svc),
        mkFilter(cc.coin.SvcReward.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.AppRewardCoupon.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.ValidatorRewardCoupon.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.OpenMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.IssuingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.SummarizingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.ClosedMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.AppRewardCoupon.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.ValidatorRewardCoupon.COMPANION)(co => co.payload.svc == svc),
        // TODO(M3-46): copy more of the filter over from SvcStore, as we merge more triggers and console commands
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
      val middleTickDuration = CNNodeUtil.relTimeToDuration(
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
