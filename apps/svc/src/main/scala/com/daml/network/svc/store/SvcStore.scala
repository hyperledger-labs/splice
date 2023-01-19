package com.daml.network.svc.store

import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.store.{AcsStore, CoinAppStore}
import com.daml.network.svc.store.memory.InMemorySvcStore
import com.daml.network.util.{CoinUtil, JavaContract as Contract}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}

import java.time.{Duration, Instant}
import scala.concurrent.{ExecutionContext, Future}

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait SvcStore extends CoinAppStore {

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  /** Audit log store */
  // TODO(tech-debt): build common infrastructure for such audit-log stores and inline its functions
  val events: SvcEventsStore

  // TODO(#2241) consider removing this once coin rules are created by the SV app
  def lookupCoinRulesWithOffset(): Future[
    QueryResult[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]]
  ] =
    acs.findContractWithOffset(cc.coin.CoinRules.COMPANION)(_ => true)

  // TODO(#2241) consider removing this once coin rules are created by the SV app
  def lookupCoinRules(): Future[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]] =
    lookupCoinRulesWithOffset().map(_.value)

  // TODO(#2241) consider removing this once coin rules are created by the SV app
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
    acs.findContractWithOffset(cn.svcrules.SvcRules.COMPANION)(_ => true)

  // TODO(#2241) move to SV app once ready to move away from mock SVC bootstrap
  def lookupSvcRules()(implicit
      ec: ExecutionContext
  ): Future[Option[Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]]] =
    lookupSvcRulesWithOffset().map(_.value)

  // TODO(#2241) move to SV app once ready to move away from mock SVC bootstrap
  def getSvcRules(
  )(implicit
      ec: ExecutionContext
  ): Future[Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]] =
    lookupSvcRules().map(
      _.getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription("No active SvcRules contract")
        )
      )
    )

  def lookupValidatorRightByPartyWithOffset(
      party: PartyId
  ): Future[
    QueryResult[Option[Contract[cc.coin.ValidatorRight.ContractId, cc.coin.ValidatorRight]]]
  ] =
    acs.findContractWithOffset(cc.coin.ValidatorRight.COMPANION)(co =>
      co.payload.user == party.toProtoPrimitive
    )

  /** Lookup the triple of open mining rounds that should always be present after boostrapping. */
  def lookupOpenMiningRoundTriple()(implicit
      ec: ExecutionContext
  ): Future[Option[SvcStore.OpenMiningRoundTriple]] =
    for {
      openMiningRounds <- acs.listContracts(cc.round.OpenMiningRound.COMPANION)
      result = openMiningRounds.sortBy(contract => contract.payload.round.number) match {
        case Seq(oldest, middle, newest) =>
          Some(SvcStore.OpenMiningRoundTriple(oldest = oldest, middle = middle, newest = newest))
        case _ => None
      }
    } yield result

  def lookupLatestActiveOpenMiningRound()(implicit
      ec: ExecutionContext
  ): Future[Option[SvcStore.OpenMiningRoundContract]] =
    lookupOpenMiningRoundTriple().map(_.map(_.newest))

  /** get the latest active open mining round contract, which should always be present after bootstrapping. */
  def getLatestActiveOpenMiningRound()(implicit
      ec: ExecutionContext
  ): Future[SvcStore.OpenMiningRoundContract] = lookupLatestActiveOpenMiningRound().map(
    _.getOrElse(
      throw new StatusRuntimeException(
        Status.NOT_FOUND.withDescription("No active OpenMiningRound contract")
      )
    )
  )

  /** List issuing mining rounds past their targetClosesAt */
  def listExpiredIssuingMiningRounds(now: CantonTimestamp, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[Seq[Contract[cc.round.IssuingMiningRound.ContractId, cc.round.IssuingMiningRound]]] =
    acs
      .listContracts(cc.round.IssuingMiningRound.COMPANION)
      .map(
        _.iterator
          .filter(e => now.toInstant.isAfter(e.payload.targetClosesAt))
          .take(limit)
          .toSeq
      )

  /** List coins that are expired and can never be used as transfer input. */
  def listExpiredCoins(now: CantonTimestamp, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[Seq[Contract[cc.coin.Coin.ContractId, cc.coin.Coin]]] = for {
    maybeLatestOpenMiningRound <- lookupLatestActiveOpenMiningRound()
    result <- maybeLatestOpenMiningRound.fold(
      Future.successful(Seq.empty[Contract[cc.coin.Coin.ContractId, cc.coin.Coin]])
    ) { latest =>
      acs
        .listContracts(
          cc.coin.Coin.COMPANION,
          (e: Contract[
            cc.coin.Coin.ContractId,
            cc.coin.Coin,
          ]) => CoinUtil.coinExpiresAt(e.payload).number <= latest.payload.round.number - 2,
        )
        .map(_.iterator.take(limit).toSeq)
    }
  } yield result

  /** List locked coins that are expired and can never be used as transfer input. */
  def listLockedExpiredCoins(now: CantonTimestamp, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[Seq[Contract[cc.coin.LockedCoin.ContractId, cc.coin.LockedCoin]]] = for {
    maybeLatestOpenMiningRound <- lookupLatestActiveOpenMiningRound()
    result <- maybeLatestOpenMiningRound.fold(
      Future.successful(Seq.empty[Contract[cc.coin.LockedCoin.ContractId, cc.coin.LockedCoin]])
    ) { latest =>
      acs
        .listContracts(
          cc.coin.LockedCoin.COMPANION,
          (e: Contract[
            cc.coin.LockedCoin.ContractId,
            cc.coin.LockedCoin,
          ]) => CoinUtil.coinExpiresAt(e.payload.coin).number <= latest.payload.round.number - 2,
        )
        .map(_.iterator.take(limit).toSeq)
    }
  } yield result

  def getTotalsForRound(round: Long): SvcStore.RoundTotals = {
    val transfers = events.getTransferSummariesPerRound(round)
    transfers.foldLeft(SvcStore.RoundTotals())((t, transfer) => {
      SvcStore.RoundTotals(
        t.transferFees + transfer.totalTransferFees,
        t.adminFees + transfer.senderAdminFees,
        t.holdingFees + transfer.senderHoldingFees,
        t.transferInputs + transfer.inAmount,
        t.nonSelfTransferOutputs + transfer.nonSelfOutAmount,
        t.selfTransferOutputs + transfer.selfOutAmount,
      )
    })
  }

  def lookupFeaturedAppByProviderWithOffset(
      provider: String
  ): Future[QueryResult[Option[Contract[FeaturedAppRight.ContractId, FeaturedAppRight]]]] =
    acs.findContractWithOffset(FeaturedAppRight.COMPANION)(co => co.payload.provider == provider)
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
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContext
  ): SvcStore =
    storage match {
      case _: MemoryStorage => new InMemorySvcStore(svcParty = svcParty, loggerFactory)
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
        mkFilter(cc.coin.CoinRulesRequest.COMPANION)(co => co.payload.svc == svc),
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
    def readyToAdvanceAt(tickDuration: Duration): Instant = {
      Ordering[Instant].max(
        oldest.payload.targetClosesAt,
        Ordering[Instant].max(
          // TODO(M3-07): when changing CoinConfigs it will make sense to store tickDuration on the rounds and express targetClosesAt as 2 * tickDuration
          middle.payload.opensAt.plus(tickDuration),
          newest.payload.opensAt,
        ),
      )
    }

    def toSeq: Seq[OpenMiningRoundContract] = Seq(oldest, middle, newest)
  }
}
