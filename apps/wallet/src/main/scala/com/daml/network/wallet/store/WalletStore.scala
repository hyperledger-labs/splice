package com.daml.network.wallet.store

import cats.syntax.traverseFilter.*
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.store.{
  CNNodeAppStoreWithoutHistory,
  InMemoryMultiDomainAcsStore,
  TxLogStore,
  ConfiguredDefaultDomain,
}
import com.daml.network.util.Contract
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** A store for serving all queries used by the wallet backend's gRPC request handlers and automation
  * that require the visibility of the validator user.
  */
trait WalletStore extends CNNodeAppStoreWithoutHistory with ConfiguredDefaultDomain {

  protected implicit val ec: ExecutionContext

  /** The key identifying the parties considered by this store. */
  def walletKey: WalletStore.Key

  // TODO (#6227): Remove this override so that multiDomainAcsStore is just the generic MultiDomainAcsStore
  def multiDomainAcsStore: InMemoryMultiDomainAcsStore[
    TxLogStore.IndexRecord,
    TxLogStore.Entry[TxLogStore.IndexRecord],
  ]

  def lookupInstallByParty(
      endUserParty: PartyId
  )(implicit tc: TraceContext): Future[Option[
    Contract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
  ]] = for {
    domainId <- defaultAcsDomainIdF
    // TODO (#6227): Replace with a wallet-specific lookup
    install <- multiDomainAcsStore.findContractOnDomain(installCodegen.WalletAppInstall.COMPANION)(
      domainId,
      (co: Contract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]) =>
        co.payload.endUserParty == endUserParty.toProtoPrimitive,
    )
  } yield install

  def lookupInstallByName(
      endUserName: String
  )(implicit tc: TraceContext): Future[Option[
    Contract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
  ]] = for {
    domainId <- defaultAcsDomainIdF
    // TODO (#6227): Replace with a wallet-specific lookup
    install <- multiDomainAcsStore.findContractOnDomain(installCodegen.WalletAppInstall.COMPANION)(
      domainId,
      (co: Contract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]) =>
        co.payload.endUserName == endUserName,
    )
  } yield install

  def lookupValidatorFeaturedAppRight()(implicit
      tc: TraceContext
  ): Future[Option[Contract[FeaturedAppRight.ContractId, coinCodegen.FeaturedAppRight]]] =
    defaultAcsDomainIdF.flatMap(
      // TODO (#6227): Replace with a wallet-specific lookup
      multiDomainAcsStore.findContractOnDomain(coinCodegen.FeaturedAppRight.COMPANION)(
        _,
        (co: Contract[FeaturedAppRight.ContractId, coinCodegen.FeaturedAppRight]) =>
          co.payload.provider == walletKey.validatorParty.toProtoPrimitive,
      )
    )

  def listUsersWithArchivedWalletInstalls(
      usernames: Seq[String],
      limit: Integer,
  )(implicit tc: TraceContext): Future[Seq[String]] = {
    usernames.toList
      .filterA(lookupInstallByName(_).map(!_.isDefined))
      .map(_.take(limit))
  }
}

object WalletStore {
  case class Key(
      /** The validator party. */
      validatorParty: PartyId,
      /** The party-id of the SVC issuing CC managed by this wallet. */
      svcParty: PartyId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("validatorParty", _.validatorParty),
      param("svcParty", _.svcParty),
    )
  }
}
