package com.daml.network.wallet.store

import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.codegen.java.cc.coin.{CoinRules, ValidatorRight}
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cc.{coin as coinCodegen, round as roundCodegen}
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.environment.CoinRetries
import com.daml.network.store.{AcsStore, CoinAppStore, StoreWithOpenMiningRounds}
import com.daml.network.util.JavaContract
import com.daml.network.wallet.store.memory.InMemoryWalletStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

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

  def getCoinRules()(implicit
      ec: ExecutionContext
  ): Future[JavaContract[coinCodegen.CoinRules.ContractId, coinCodegen.CoinRules]] =
    acs
      .findContractWithOffset(coinCodegen.CoinRules.COMPANION)(_ => true)
      .map(
        _.value.getOrElse(
          throw new StatusRuntimeException(
            Status.NOT_FOUND.withDescription("No active CoinRules contract")
          )
        )
      )

  /** Get the context required for executing a transfer.
    */
  def getPaymentTransferContext(retryProvider: CoinRetries, now: CantonTimestamp)(implicit
      ec: ExecutionContext
  ): Future[v1.coin.PaymentTransferContext] =
    for {
      coinRules <- getCoinRules()
      openRound <- getLatestOpenMiningRound(now)
      issuingMiningRounds <- acs.listContracts(roundCodegen.IssuingMiningRound.COMPANION)
      validatorRights <- acs.listContracts(coinCodegen.ValidatorRight.COMPANION)
    } yield {
      val openIssuingRounds = issuingMiningRounds
        .filter(c => c.payload.opensAt.isBefore(now.toInstant))
        .map(r =>
          (r.payload.round, r.contractId.toInterface(v1.round.IssuingMiningRound.INTERFACE))
        )
        .toMap[v1.round.Round, v1.round.IssuingMiningRound.ContractId]
        .asJava
      val transferContext = new v1.coin.TransferContext(
        openRound.contractId.toInterface(v1.round.OpenMiningRound.INTERFACE),
        openIssuingRounds,
        validatorRights
          .map(r => (r.payload.user, r.contractId.toInterface(v1.coin.ValidatorRight.INTERFACE)))
          .toMap[String, v1.coin.ValidatorRight.ContractId]
          .asJava,
        // Note: featured app rights are ignored for transfers with sender == provider, which is the case for all
        // transfers issued by the wallet. We therefore do not provide a FeaturedAppRight, even if the user has one.
        None.toJava,
      )
      new v1.coin.PaymentTransferContext(
        coinRules.contractId.toInterface(v1.coin.CoinRules.INTERFACE),
        transferContext,
      )
    }
}

object WalletStore {
  def apply(
      key: Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      timeouts: ProcessingTimeout,
  )(implicit
      ec: ExecutionContext
  ): WalletStore =
    storage match {
      case _: MemoryStorage => new InMemoryWalletStore(key, loggerFactory, timeouts)
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
      ),
    )
  }

}
