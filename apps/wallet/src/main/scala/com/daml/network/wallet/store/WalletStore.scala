package com.daml.network.wallet.store

import com.daml.network.codegen.java.cc.coin.{CoinRules, ValidatorRight}
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cc.{coin as coinCodegen, round as roundCodegen}
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.store.{AcsStore, CoinAppStore, StoreWithOpenMiningRounds}
import com.daml.network.util.JavaContract
import com.daml.network.wallet.store.memory.InMemoryWalletStore
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future}
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight

/** A store for serving all queries used by the wallet backend's gRPC request handlers and automation
  * that require the visibility of the validator user.
  */
trait WalletStore extends CoinAppStore with StoreWithOpenMiningRounds {

  protected implicit val ec: ExecutionContext

  /** The key identifying the parties considered by this store. */
  def key: WalletStore.Key

  def lookupInstallByParty(
      endUserParty: PartyId
  ): Future[Option[
    JavaContract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
  ]] =
    acs
      .findContractWithOffset(installCodegen.WalletAppInstall.COMPANION)(co =>
        co.payload.endUserParty == endUserParty.toProtoPrimitive
      )
      .map(_.value)

  def lookupInstallByName(
      endUserName: String
  ): Future[Option[
    JavaContract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
  ]] =
    acs
      .findContractWithOffset(installCodegen.WalletAppInstall.COMPANION)(co =>
        co.payload.endUserName == endUserName
      )
      .map(_.value)

  def getCoinRules()
      : Future[JavaContract[coinCodegen.CoinRules.ContractId, coinCodegen.CoinRules]] =
    acs
      .findContractWithOffset(coinCodegen.CoinRules.COMPANION)(_ => true)
      .map(
        _.value.getOrElse(
          throw new StatusRuntimeException(
            Status.NOT_FOUND.withDescription("No active CoinRules contract")
          )
        )
      )

  def getOpenIssuingRounds(
      now: CantonTimestamp
  ): Future[Seq[JavaContract[IssuingMiningRound.ContractId, IssuingMiningRound]]] = {
    acs
      .listContracts(roundCodegen.IssuingMiningRound.COMPANION)
      .map(rounds => rounds.filter(c => c.payload.opensAt.isBefore(now.toInstant)))
  }

  def lookupValidatorFeaturedAppRight()
      : Future[Option[JavaContract[FeaturedAppRight.ContractId, coinCodegen.FeaturedAppRight]]] = {
    acs.findContract(coinCodegen.FeaturedAppRight.COMPANION)(co =>
      co.payload.provider == key.validatorParty.toProtoPrimitive
    )
  }
}

object WalletStore {
  def apply(
      key: Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      timeouts: ProcessingTimeout,
      futureSupervisor: FutureSupervisor,
  )(implicit
      ec: ExecutionContext
  ): WalletStore =
    storage match {
      case _: MemoryStorage =>
        new InMemoryWalletStore(key, loggerFactory, timeouts, futureSupervisor)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

  case class Key(
      /** The party used by the wallet service user to act on behalf of it's users. */
      walletServiceParty: PartyId,
      /** The validator party. */
      validatorParty: PartyId,
      /** The validator user name. */
      validatorUserName: String,
      /** The party-id of the SVC issuing CC managed by this wallet. */
      svcParty: PartyId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("walletServiceParty", _.walletServiceParty),
      param("validatorParty", _.validatorParty),
      param("svcParty", _.svcParty),
    )
  }

  /** Contract of a wallet store for a specific wallet-service party. */
  def contractFilter(key: Key): AcsStore.ContractFilter = {
    import AcsStore.mkFilter
    val walletService = key.walletServiceParty.toProtoPrimitive
    val validator = key.validatorParty.toProtoPrimitive
    val svc = key.svcParty.toProtoPrimitive

    AcsStore.SimpleContractFilter(
      key.validatorParty,
      Map(
        mkFilter(installCodegen.WalletAppInstall.COMPANION)(co =>
          co.payload.walletServiceParty == walletService &&
            co.payload.validatorParty == validator &&
            co.payload.svcParty == svc
        ),
        mkFilter(OpenMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(IssuingMiningRound.COMPANION)(co => co.payload.svc == svc),
        mkFilter(ValidatorRight.COMPANION)(co =>
          // All validator rights that entitle the endUser to collect rewards as a validator operator
          co.payload.svc == svc &&
            co.payload.validator == validator
        ),
        mkFilter(CoinRules.COMPANION)(co => co.payload.svc == svc),
        mkFilter(FeaturedAppRight.COMPANION)(co =>
          co.payload.svc == svc && co.payload.provider == validator
        ),
      ),
    )
  }

}
