package com.daml.network.svc.store

import com.daml.network.codegen.java.cc
import com.daml.network.store.AcsStore
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.svc.store.memory.InMemorySvcStore
import com.daml.network.util.JavaContract as Contract
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}

import java.time.{Duration, Instant}
import scala.concurrent.{ExecutionContext, Future}

/** Utility class grouping the two kinds of stores managed by the SvcApp. */
trait SvcStore extends AutoCloseable {

  /** Get the party-id of the SVC issuing CC accepted by this provider. */
  def svcParty: PartyId

  /** Audit log store */
  // TODO(tech-debt): build common infrastructure for such audit-log stores and inline its functions
  val events: SvcEventsStore

  /** The sink to use for ingesting data from the ledger into this store. */
  val acsIngestionSink: AcsStore.IngestionSink

  /** The [[com.daml.network.store.AcsStore]] used to back the default implementation of the queries. */
  val acs: AcsStore

  def lookupCoinRules(
  ): Future[
    QueryResult[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]]
  ] =
    acs.findContract(cc.coin.CoinRules.COMPANION)(_ => true)

  def getCoinRules(
  )(implicit
      ec: ExecutionContext
  ): Future[QueryResult[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]] =
    lookupCoinRules().map(
      _.map(
        _.getOrElse(
          throw new StatusRuntimeException(
            Status.NOT_FOUND.withDescription("No active CoinRules contract")
          )
        )
      )
    )

  def lookupValidatorRightByParty(
      party: PartyId
  ): Future[
    QueryResult[Option[Contract[cc.coin.ValidatorRight.ContractId, cc.coin.ValidatorRight]]]
  ] =
    acs.findContract(cc.coin.ValidatorRight.COMPANION)(co =>
      co.payload.user == party.toProtoPrimitive
    )

  /** Lookup the triple of open mining rounds that should always be present after boostrapping. */
  def lookupOpenMiningRoundTriple()(implicit
      ec: ExecutionContext
  ): Future[QueryResult[Option[SvcStore.OpenMiningRoundTriple]]] =
    for {
      QueryResult(off, openMiningRounds) <- acs.listContracts(cc.round.OpenMiningRound.COMPANION)
      result = openMiningRounds.sortBy(contract => contract.payload.round.number) match {
        case Seq(oldest, middle, newest) =>
          Some(SvcStore.OpenMiningRoundTriple(oldest = oldest, middle = middle, newest = newest))
        case _ => None
      }
    } yield QueryResult(off, result)

  /** List issuing mining rounds past their targetClosesAt */
  def listExpiredIssuingMiningRounds(now: CantonTimestamp, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[QueryResult[
    Seq[Contract[cc.round.IssuingMiningRound.ContractId, cc.round.IssuingMiningRound]]
  ]] =
    acs
      .listContracts(cc.round.IssuingMiningRound.COMPANION)
      .map(
        _.map(entries =>
          entries.iterator
            .filter(e => now.toInstant.isAfter(e.payload.targetClosesAt))
            .take(limit)
            .toSeq
        )
      )

  def getTotalsForRound(round: Long): SvcStore.RoundTotals = {
    val transfers = events.getTransferSummariesPerRound(round)
    transfers.foldLeft(SvcStore.RoundTotals())((t, transfer) => {
      SvcStore.RoundTotals(
        t.transferFees + transfer.totalTransferFees,
        t.adminFees + transfer.senderAdminFees,
        t.holdingFees + transfer.senderHoldingFees,
        t.transferInputs + transfer.inQuantity,
        t.nonSelfTransferOutputs + transfer.nonSelfOutQuantity,
        t.selfTransferOutputs + transfer.selfOutQuantity,
      )
    })
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
        mkFilter(cc.coin.ValidatorRight.COMPANION)(co =>
          co.payload.svc == svc && co.payload.validator == svc && co.payload.user == svc
        ),
        mkFilter(cc.round.OpenMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.ClosedMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.IssuingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.SummarizingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.AppReward.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.ValidatorReward.COMPANION)(co => co.payload.svc == svc),
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
