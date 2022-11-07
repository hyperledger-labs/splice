package com.daml.network.validator.store

import com.daml.network.codegen.java.cc.{coin => coinCodegen, coinrules => coinRulesCodegen}
import com.daml.network.codegen.java.cn.{wallet => walletCodegen}
import com.daml.network.store.JavaAcsStore.QueryResult
import com.daml.network.store.{JavaAcsStore => AcsStore}
import com.daml.network.util.{JavaContract => Contract}
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
  ): Future[QueryResult[
    Option[Contract[walletCodegen.WalletAppInstall.ContractId, walletCodegen.WalletAppInstall]]
  ]] =
    acsStore.findContract(walletCodegen.WalletAppInstall.COMPANION)(co =>
      co.payload.endUserName == endUserName
    )

  def lookupCoinRules(): Future[
    QueryResult[Option[Contract[coinRulesCodegen.CoinRules.ContractId, coinRulesCodegen.CoinRules]]]
  ] =
    acsStore.findContract(coinRulesCodegen.CoinRules.COMPANION)(_ => true)

  def lookupCoinRulesRequest(): Future[QueryResult[Option[
    Contract[coinRulesCodegen.CoinRulesRequest.ContractId, coinRulesCodegen.CoinRulesRequest]
  ]]] =
    acsStore.findContract(coinRulesCodegen.CoinRulesRequest.COMPANION)(_ => true)

  def lookupValidatorRightByParty(
      party: PartyId
  ): Future[
    QueryResult[Option[Contract[coinCodegen.ValidatorRight.ContractId, coinCodegen.ValidatorRight]]]
  ] =
    acsStore.findContract(coinCodegen.ValidatorRight.COMPANION)(co =>
      co.payload.user == party.toPrim
    )
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
        mkFilter(walletCodegen.WalletAppInstall.COMPANION)(co =>
          co.payload.walletServiceParty == walletService &&
            co.payload.validatorParty == validator &&
            co.payload.svcParty == svc
        ),
        mkFilter(coinRulesCodegen.CoinRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(coinRulesCodegen.CoinRulesRequest.COMPANION)(co =>
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
