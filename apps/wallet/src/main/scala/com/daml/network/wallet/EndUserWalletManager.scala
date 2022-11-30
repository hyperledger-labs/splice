package com.daml.network.wallet

import akka.stream.Materializer
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.install.WalletAppInstall
import com.daml.network.config.AutomationConfig
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.JavaContract as Contract
import com.daml.network.wallet.store.{EndUserWalletStore, WalletStore}
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

/** Manages all services comprising an end-user wallets. */
class EndUserWalletManager(
    ledgerClient: CoinLedgerClient,
    val store: WalletStore,
    automationConfig: AutomationConfig,
    clockConfig: ClockConfig,
    storage: Storage,
    retryProvider: CoinRetries,
    override val loggerFactory: NamedLoggerFactory,
    timeouts: ProcessingTimeout,
)(implicit ec: ExecutionContext, mat: Materializer, tracer: Tracer)
    extends AutoCloseable
    with NamedLogging {

  // map from end user name to end-user treasury service
  private[this] val endUserWalletsMap
      : scala.collection.concurrent.Map[String, EndUserWalletService] = TrieMap.empty

  /** Lookup an end-user's wallet.
    *
    * Succeeds if the user has been onboarded and its wallet has been initialized.
    */
  final def lookupEndUserWallet(endUserName: String): Option[EndUserWalletService] =
    endUserWalletsMap.get(endUserName)

  final def endUserWallets: Iterable[EndUserWalletService] = endUserWalletsMap.values

  /** Get or create the store for an end-user. Intended to be called when a user is onboarded.
    *
    * Do not use this in request handlers to avoid leaking resources.
    *
    * @return true, if a new end-user wallet was created
    */
  final def getOrCreateEndUserWallet(
      install: Contract[WalletAppInstall.ContractId, WalletAppInstall]
  ): Boolean = {
    val endUserName = install.payload.endUserName
    val endUserParty = PartyId.tryFromProtoPrimitive(install.payload.endUserParty)
    val key =
      EndUserWalletStore.Key(svcParty = store.key.svcParty, endUserName, endUserParty)
    val walletService = new EndUserWalletService(
      ledgerClient,
      install,
      key,
      this,
      automationConfig: AutomationConfig,
      clockConfig: ClockConfig,
      storage,
      retryProvider,
      loggerFactory,
      timeouts,
    )

    endUserWalletsMap
      .putIfAbsent(endUserName, walletService)
      .fold(true)(_ => {
        walletService.close()
        false
      })
  }

  // TODO(M1-52): this function probably needs restructuring to integrate it with automation rewards collection; e.g., make it streaming
  // NOTE: this function is exposed here in the EndUserWalletManager, as it requires joining data from all user-stores.
  def listValidatorRewardsCollectableBy(
      validatorUserStore: EndUserWalletStore
  )(implicit
      tc: TraceContext
  ): Future[Seq[Contract[coinCodegen.ValidatorReward.ContractId, coinCodegen.ValidatorReward]]] =
    for {
      QueryResult(_, validatorRights) <- validatorUserStore.listContracts(
        coinCodegen.ValidatorRight.COMPANION
      )
      users = validatorRights.map(c => PartyId.tryFromProtoPrimitive(c.payload.user)).toSet
      validatorRewardsFs: Seq[
        Future[Seq[Contract[coinCodegen.ValidatorReward.ContractId, coinCodegen.ValidatorReward]]]
      ] = users.toSeq
        .map(u =>
          store.lookupInstallByParty(u).flatMap {
            case QueryResult(_, None) =>
              logger.warn(
                s"ValidatorRight of ${validatorUserStore.key.endUserParty} for end-user party $u has no associated WalletAppInstall contract."
              )
              Future.successful(Seq.empty)
            case QueryResult(_, Some(install)) =>
              this.lookupEndUserWallet(install.payload.endUserName) match {
                case None =>
                  logger.warn(
                    s"Might miss validator rewards as the EndUserWalletStore for end-user name ${install.payload.endUserName} is not (yet) setup."
                  )
                  Future.successful(Seq.empty)
                case Some(userWallet) =>
                  userWallet.store.listContracts(coinCodegen.ValidatorReward.COMPANION).map(_.value)
              }
          }
        )
      validatorRewards <- Future.sequence(validatorRewardsFs)
    } yield validatorRewards.flatten

  override def close(): Unit = Lifecycle.close(endUserWalletsMap.values.toSeq *)(logger)
}
