package com.daml.network.wallet.treasury

import akka.stream.Materializer
import com.daml.network.codegen.CN.Wallet.WalletAppInstall
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.util.Contract
import com.daml.network.wallet.admin.grpc.EndUserTreasuryService
import com.daml.network.wallet.store.{EndUserWalletStore, WalletStore}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}

import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext

/** Wrapper around a map that holds all of the treasury services for each on-boarded wallet end user. */
class TreasuryServices(
    connection: CoinLedgerConnection,
    override val loggerFactory: NamedLoggerFactory,
    timeouts: ProcessingTimeout,
)(implicit ec: ExecutionContext, mat: Materializer)
    extends AutoCloseable
    with NamedLogging {

  // map from end user name to end-user treasury service
  private val treasuries: scala.collection.concurrent.Map[String, EndUserTreasuryService] =
    TrieMap()

  def addOrCreateTreasuryService(
      install: Contract[WalletAppInstall],
      walletStoreKey: WalletStore.Key,
      userStore: EndUserWalletStore,
  ): EndUserTreasuryService = treasuries.getOrElseUpdate(
    userStore.key.endUserName,
    EndUserTreasuryService(
      connection,
      install,
      walletStoreKey,
      userStore,
      loggerFactory,
      timeouts,
    ),
  )

  /** Lookup an end-user's treasury service.
    * Succeeds if the user has been onboarded and its treasury has been initialized.
    */
  final def lookupEndUserTreasury(endUserName: String): Option[EndUserTreasuryService] =
    treasuries.get(endUserName)

  override def close(): Unit = Lifecycle.close(treasuries.values.toSeq *)(logger)
}
