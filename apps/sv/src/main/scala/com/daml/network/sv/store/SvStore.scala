package com.daml.network.sv.store

import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.store.{AcsStore, CoinAppStoreWithoutHistory}
import com.daml.network.sv.store.memory.InMemorySvStore
import com.daml.network.util.JavaContract as Contract
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future}

trait SvStore extends CoinAppStoreWithoutHistory {

  def key: SvStore.Key

  def lookupCoinRulesWithOffset(
  ): Future[
    QueryResult[Option[Contract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]]]
  ] =
    acs.findContractWithOffset(cc.coin.CoinRules.COMPANION)(_ => true)

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

  def lookupSvcRulesWithOffset(
  ): Future[
    QueryResult[Option[Contract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules]]]
  ] =
    acs.findContractWithOffset(cn.svcrules.SvcRules.COMPANION)(_ => true)

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
}

object SvStore {
  def apply(
      key: Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      futureSupervisor: FutureSupervisor,
  )(implicit ec: ExecutionContext): SvStore =
    storage match {
      case _: MemoryStorage => new InMemorySvStore(key, loggerFactory, futureSupervisor)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  case class Key(
      /** The party-id of the SV that this store belongs to. */
      svParty: PartyId,
      /** The party-id of the SVC issuing CC accepted by this provider. */
      svcParty: PartyId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("svParty", _.svParty),
      param("svcParty", _.svcParty),
    )
  }

  /** Contract filter of an sv acs store for a specific acs party. */
  def contractFilter(svcParty: PartyId): AcsStore.ContractFilter = {
    import AcsStore.mkFilter
    val svc = svcParty.toProtoPrimitive

    AcsStore.SimpleContractFilter(
      svcParty,
      Map(
        mkFilter(cc.coin.CoinRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.coin.CoinRulesRequest.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cn.svcrules.SvcRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.OpenMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(cc.round.IssuingMiningRound.COMPANION)(co => co.payload.svc == svc),
        // TODO(M3-46): copy more of the filter over from SvcStore, as we merge more triggers and console commands
      ),
    )
  }
}
