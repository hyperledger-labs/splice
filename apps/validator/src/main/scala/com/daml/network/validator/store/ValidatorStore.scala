package com.daml.network.validator.store

import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.codegen.{CC => coinCodegen}
import com.daml.network.store.AcsStore
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.Contract
import com.daml.network.validator.store.memory.InMemoryValidatorStore
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.{ExecutionContext, Future}

trait ValidatorStore extends AutoCloseable with NamedLogging {

  /** The key identifying the parties considered by this store. */
  val key: ValidatorStore.Key

  /** The sink to use for ingesting data from the ledger into this store. */
  val acsIngestionSink: AcsStore.IngestionSink

  protected val acsStore: AcsStore

  def lookupWalletInstallByName(
      endUserName: String
  ): Future[QueryResult[Option[Contract[walletCodegen.WalletAppInstall]]]] =
    acsStore.findContract(walletCodegen.WalletAppInstall)(co =>
      co.payload.endUserName == endUserName
    )

  def lookupCoinRules(): Future[QueryResult[Option[Contract[coinCodegen.CoinRules.CoinRules]]]] =
    acsStore.findContract(coinCodegen.CoinRules.CoinRules)(_ => true)

  def lookupCoinRulesRequest()
      : Future[QueryResult[Option[Contract[coinCodegen.CoinRules.CoinRulesRequest]]]] =
    acsStore.findContract(coinCodegen.CoinRules.CoinRulesRequest)(_ => true)

  def lookupValidatorRightByParty(
      party: PartyId
  ): Future[QueryResult[Option[Contract[coinCodegen.Coin.ValidatorRight]]]] =
    acsStore.findContract(coinCodegen.Coin.ValidatorRight)(co => co.payload.user == party.toPrim)
}

object ValidatorStore {
  def apply(
      key: Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext): ValidatorStore =
    storage match {
      case _: MemoryStorage =>
        new InMemoryValidatorStore(key, loggerFactory)
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
    val walletService = key.walletServiceParty.toPrim
    val validator = key.validatorParty.toPrim
    val svc = key.svcParty.toPrim

    AcsStore.SimpleContractFilter(
      key.validatorParty,
      Map(
        mkFilter(walletCodegen.WalletAppInstall)(co =>
          co.payload.walletServiceParty == walletService &&
            co.payload.validatorParty == validator &&
            co.payload.svcParty == svc
        ),
        mkFilter(coinCodegen.CoinRules.CoinRules)(co => co.payload.svc == svc),
        mkFilter(coinCodegen.CoinRules.CoinRulesRequest)(co =>
          co.payload.user == validator &&
            co.payload.svc == svc
        ),
        mkFilter(coinCodegen.Coin.ValidatorRight)(co =>
          co.payload.validator == validator &&
            co.payload.svc == svc
        ),
      ),
    )
  }

}
