package com.daml.network.validator.store

import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cc.validatorlicense as validatorLicenseCodegen
import com.daml.network.codegen.java.cn.wallet.install as walletCodegen
import com.daml.network.environment.CoinRetries
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.store.{AcsStore, CoinAppStoreWithoutHistory}
import com.daml.network.util.Contract
import com.daml.network.validator.config.ValidatorDomainConfig
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

  protected[this] def domainConfig: ValidatorDomainConfig

  override final def defaultAcsDomain = domainConfig.global

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

  def lookupValidatorLicenseWithOffset(): Future[
    QueryResult[Option[Contract[
      validatorLicenseCodegen.ValidatorLicense.ContractId,
      validatorLicenseCodegen.ValidatorLicense,
    ]]]
  ] =
    defaultAcs.flatMap(
      _.findContractWithOffset(validatorLicenseCodegen.ValidatorLicense.COMPANION)(vl =>
        vl.payload.validator == key.validatorParty.toProtoPrimitive
      )
    )

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

  def listUsers(): Future[Seq[String]] = {
    for {
      acs <- defaultAcs
      installs <- acs.listContracts(walletCodegen.WalletAppInstall.COMPANION)
    } yield installs.map(i => i.payload.endUserName)
  }
}

object ValidatorStore {
  def apply(
      key: Key,
      storage: Storage,
      domains: ValidatorDomainConfig,
      loggerFactory: NamedLoggerFactory,
      futureSupervisor: FutureSupervisor,
      retryProvider: CoinRetries,
  )(implicit ec: ExecutionContext): ValidatorStore =
    storage match {
      case _: MemoryStorage =>
        new InMemoryValidatorStore(key, domains, loggerFactory, futureSupervisor, retryProvider)
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
        mkFilter(validatorLicenseCodegen.ValidatorLicense.COMPANION)(co =>
          co.payload.validator == validator && co.payload.svc == svc
        ),
        mkFilter(coinCodegen.ValidatorRight.COMPANION)(co =>
          co.payload.validator == validator &&
            co.payload.svc == svc
        ),
      ),
    )
  }

}
