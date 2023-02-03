package com.daml.network.validator.store

import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.install as walletCodegen
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.store.{AcsStore, CoinAppStoreWithoutHistory}
import com.daml.network.util.Contract
import com.daml.network.validator.store.memory.InMemoryValidatorStore
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}

trait ValidatorStore extends CoinAppStoreWithoutHistory {

  /** The key identifying the parties considered by this store. */
  val key: ValidatorStore.Key

  def lookupWalletInstallByNameWithOffset(
      endUserName: String
  ): Future[QueryResult[
    Option[Contract[walletCodegen.WalletAppInstall.ContractId, walletCodegen.WalletAppInstall]]
  ]] =
    defaultAcs.flatMap(
      _.findContractWithOffset(walletCodegen.WalletAppInstall.COMPANION)(co =>
        co.payload.endUserName == endUserName
      )
    )

  def lookupCoinRulesWithOffset(): Future[
    QueryResult[Option[Contract[coinCodegen.CoinRules.ContractId, coinCodegen.CoinRules]]]
  ] =
    defaultAcs.flatMap(_.findContractWithOffset(coinCodegen.CoinRules.COMPANION)(_ => true))

  def lookupCoinRulesRequestWithOffset(): Future[QueryResult[Option[
    Contract[coinCodegen.CoinRulesRequest.ContractId, coinCodegen.CoinRulesRequest]
  ]]] =
    defaultAcs.flatMap(_.findContractWithOffset(coinCodegen.CoinRulesRequest.COMPANION)(_ => true))

  def lookupValidatorRightByPartyWithOffset(
      party: PartyId
  ): Future[
    QueryResult[Option[Contract[coinCodegen.ValidatorRight.ContractId, coinCodegen.ValidatorRight]]]
  ] =
    defaultAcs.flatMap(
      _.findContractWithOffset(coinCodegen.ValidatorRight.COMPANION)(co =>
        co.payload.user == party.toProtoPrimitive
      )
    )
}

object ValidatorStore {
  def apply(
      key: Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      futureSupervisor: FutureSupervisor,
  )(implicit ec: ExecutionContext): ValidatorStore =
    storage match {
      case _: MemoryStorage =>
        new InMemoryValidatorStore(key, loggerFactory, futureSupervisor)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  case class Key(
      /** The party used by the wallet service user to act on behalf of it's users. */
      walletServiceParty: PartyId,
      /** The validator party. */
      validatorParty: PartyId,
      /** The party-id of the SVC issuing CC managed by this wallet. */
      svcParty: PartyId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("walletServiceParty", _.walletServiceParty),
      param("validatorParty", _.validatorParty),
      param("svcParty", _.svcParty),
    )
  }

  /** Contract of a wallet store for a specific validator party. */
  def contractFilter(key: Key): AcsStore.ContractFilter = {
    import AcsStore.mkFilter
    val walletService = key.walletServiceParty.toProtoPrimitive
    val validator = key.validatorParty.toProtoPrimitive
    val svc = key.svcParty.toProtoPrimitive

    AcsStore.SimpleContractFilter(
      key.validatorParty,
      Map(
        mkFilter(walletCodegen.WalletAppInstall.COMPANION)(co =>
          co.payload.walletServiceParty == walletService &&
            co.payload.validatorParty == validator &&
            co.payload.svcParty == svc
        ),
        mkFilter(coinCodegen.CoinRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(coinCodegen.CoinRulesRequest.COMPANION)(co =>
          co.payload.user == validator &&
            co.payload.svc == svc
        ),
        mkFilter(coinCodegen.ValidatorRight.COMPANION)(co =>
          co.payload.validator == validator &&
            co.payload.svc == svc
        ),
      ),
    )
  }

}
