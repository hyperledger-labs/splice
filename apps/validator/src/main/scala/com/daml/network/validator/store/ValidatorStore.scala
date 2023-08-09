package com.daml.network.validator.store

import cats.syntax.traverseFilter.*
import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  validatorlicense as validatorLicenseCodegen,
}
import com.daml.network.codegen.java.cc.globaldomain.{
  ValidatorTraffic,
  ValidatorTrafficCreationIntent,
}
import com.daml.network.codegen.java.cn.appmanager.store as appManagerCodegen
import com.daml.network.codegen.java.cn.wallet.install as walletCodegen
import com.daml.network.codegen.java.cn.wallet.topupstate.ValidatorTopUpState
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{
  CNNodeAppStoreWithoutHistory,
  InMemoryMultiDomainAcsStore,
  MultiDomainAcsStore,
  TxLogStore,
}
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.util.{Contract, ContractWithState}
import com.daml.network.validator.config.ValidatorDomainConfig
import com.daml.network.validator.store.memory.InMemoryValidatorStore
import com.daml.network.wallet.store.WalletStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.{DbStorage, MemoryStorage, Storage}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

trait ValidatorStore extends WalletStore with CNNodeAppStoreWithoutHistory {

  /** The key identifying the parties considered by this store. */
  val key: ValidatorStore.Key

  protected[this] def domainConfig: ValidatorDomainConfig

  override final def defaultAcsDomain = domainConfig.global.alias

  override def multiDomainAcsStore: InMemoryMultiDomainAcsStore[
    TxLogStore.IndexRecord,
    TxLogStore.Entry[TxLogStore.IndexRecord],
  ]

  def lookupWalletInstallByNameWithOffset(
      endUserName: String
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[Contract[walletCodegen.WalletAppInstall.ContractId, walletCodegen.WalletAppInstall]]
  ]] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(walletCodegen.WalletAppInstall.COMPANION)(
        _,
        co => co.payload.endUserName == endUserName,
      )
    )

  def lookupValidatorLicenseWithOffset()(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[
      validatorLicenseCodegen.ValidatorLicense.ContractId,
      validatorLicenseCodegen.ValidatorLicense,
    ]]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(
        validatorLicenseCodegen.ValidatorLicense.COMPANION
      )(_, vl => vl.payload.validator == key.validatorParty.toProtoPrimitive)
    )

  def lookupValidatorRightByPartyWithOffset(
      party: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[coinCodegen.ValidatorRight.ContractId, coinCodegen.ValidatorRight]]]
  ] =
    defaultAcsDomainIdF.flatMap(
      multiDomainAcsStore.findContractOnDomainWithOffset(coinCodegen.ValidatorRight.COMPANION)(
        _,
        co => co.payload.user == party.toProtoPrimitive,
      )
    )

  /** Lookup the validator-traffic contract for the given domain. */
  def lookupValidatorTrafficWithOffset(domainId: DomainId)(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[ValidatorTraffic.ContractId, ValidatorTraffic]]]
  ] =
    defaultAcsDomainIdF.flatMap(defaultDomainId =>
      // TODO(#4913): read from all domains in the global domain
      multiDomainAcsStore.findContractOnDomainWithOffset(ValidatorTraffic.COMPANION)(
        defaultDomainId,
        traffic => traffic.payload.domainId == domainId.toProtoPrimitive,
      )
    )

  def lookupValidatorTrafficCreationIntentWithOffset(
      domainId: DomainId
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[Contract[ValidatorTrafficCreationIntent.ContractId, ValidatorTrafficCreationIntent]]
    ]
  ] =
    defaultAcsDomainIdF.flatMap(defaultDomainId =>
      // TODO(#4913): read from all domains in the global domain
      multiDomainAcsStore.findContractOnDomainWithOffset(ValidatorTrafficCreationIntent.COMPANION)(
        defaultDomainId,
        intent => intent.payload.domainId == domainId.toProtoPrimitive,
      )
    )

  def lookupValidatorTopUpStateWithOffset(
      domainId: DomainId
  ): Future[
    QueryResult[
      Option[Contract[ValidatorTopUpState.ContractId, ValidatorTopUpState]]
    ]
  ] =
    multiDomainAcsStore.findContractOnDomainWithOffset(ValidatorTopUpState.COMPANION)(
      domainId,
      intent => intent.payload.domainId == domainId.toProtoPrimitive,
    )

  def listUsers()(implicit tc: TraceContext): Future[Seq[String]] = {
    for {
      domainId <- defaultAcsDomainIdF
      installs <- multiDomainAcsStore.listContractsOnDomain(
        walletCodegen.WalletAppInstall.COMPANION,
        domainId,
      )
    } yield installs.map(i => i.payload.endUserName)
  }

  def lookupLatestAppConfiguration(
      provider: PartyId
  )(implicit tc: TraceContext): Future[Option[ContractWithState[
    appManagerCodegen.AppConfiguration.ContractId,
    appManagerCodegen.AppConfiguration,
  ]]] =
    multiDomainAcsStore
      .filterContracts(
        appManagerCodegen.AppConfiguration.COMPANION,
        (c: Contract[_, appManagerCodegen.AppConfiguration]) =>
          c.payload.provider == provider.toProtoPrimitive,
      )
      .map {
        _.maxByOption(c => c.contract.payload.version)
      }

  def lookupAppConfiguration(
      provider: PartyId,
      version: Long,
  ): Future[QueryResult[Option[ContractWithState[
    appManagerCodegen.AppConfiguration.ContractId,
    appManagerCodegen.AppConfiguration,
  ]]]] =
    multiDomainAcsStore
      .findContractWithOffset(appManagerCodegen.AppConfiguration.COMPANION)(
        (c: Contract[
          appManagerCodegen.AppConfiguration.ContractId,
          appManagerCodegen.AppConfiguration,
        ]) => c.payload.provider == provider.toProtoPrimitive && c.payload.version == version
      )

  def lookupAppRelease(
      provider: PartyId,
      version: String,
  ): Future[QueryResult[
    Option[ContractWithState[appManagerCodegen.AppRelease.ContractId, appManagerCodegen.AppRelease]]
  ]] =
    multiDomainAcsStore
      .findContractWithOffset(appManagerCodegen.AppRelease.COMPANION)(
        (c: Contract[appManagerCodegen.AppRelease.ContractId, appManagerCodegen.AppRelease]) =>
          c.payload.provider == provider.toProtoPrimitive && c.payload.version == version
      )

  def lookupRegisteredApp(
      provider: PartyId
  ): Future[QueryResult[
    Option[
      ContractWithState[appManagerCodegen.RegisteredApp.ContractId, appManagerCodegen.RegisteredApp]
    ]
  ]] =
    multiDomainAcsStore
      .findContractWithOffset(appManagerCodegen.RegisteredApp.COMPANION)(
        (c: Contract[
          appManagerCodegen.RegisteredApp.ContractId,
          appManagerCodegen.RegisteredApp,
        ]) => c.payload.provider == provider.toProtoPrimitive
      )

  def lookupInstalledApp(
      provider: PartyId
  ): Future[QueryResult[
    Option[
      ContractWithState[appManagerCodegen.InstalledApp.ContractId, appManagerCodegen.InstalledApp]
    ]
  ]] =
    multiDomainAcsStore
      .findContractWithOffset(appManagerCodegen.InstalledApp.COMPANION)(
        (c: Contract[appManagerCodegen.InstalledApp.ContractId, appManagerCodegen.InstalledApp]) =>
          c.payload.provider == provider.toProtoPrimitive
      )

  def listRegisteredApps()(implicit
      traceContext: TraceContext
  ): Future[Seq[ValidatorStore.RegisteredApp]] =
    multiDomainAcsStore.listContracts(appManagerCodegen.RegisteredApp.COMPANION).flatMap { apps =>
      apps.toList.traverseFilter { app =>
        lookupAppConfiguration(
          PartyId.tryFromProtoPrimitive(app.contract.payload.provider),
          app.contract.payload.latestConfigurationVersion,
        ).map(config =>
          config.value.map(
            ValidatorStore.RegisteredApp(
              app,
              _,
            )
          )
        )
      }
    }

  def listInstalledApps()(implicit
      traceContext: TraceContext
  ): Future[Seq[
    ContractWithState[appManagerCodegen.InstalledApp.ContractId, appManagerCodegen.InstalledApp]
  ]] =
    multiDomainAcsStore.listContracts(appManagerCodegen.InstalledApp.COMPANION)
}

object ValidatorStore {

  final case class RegisteredApp(
      registered: ContractWithState[
        appManagerCodegen.RegisteredApp.ContractId,
        appManagerCodegen.RegisteredApp,
      ],
      configuration: ContractWithState[
        appManagerCodegen.AppConfiguration.ContractId,
        appManagerCodegen.AppConfiguration,
      ],
  )

  def apply(
      key: Key,
      storage: Storage,
      domains: ValidatorDomainConfig,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
  )(implicit ec: ExecutionContext): ValidatorStore =
    storage match {
      case _: MemoryStorage =>
        new InMemoryValidatorStore(key, domains, loggerFactory, retryProvider)
      case _: DbStorage => throw new RuntimeException("Not implemented")
    }

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

  /** Contract of a wallet store for a specific validator party. */
  def contractFilter(key: Key): MultiDomainAcsStore.ContractFilter = {
    import MultiDomainAcsStore.mkFilter
    val validator = key.validatorParty.toProtoPrimitive
    val svc = key.svcParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      key.validatorParty,
      Map(
        mkFilter(walletCodegen.WalletAppInstall.COMPANION)(co =>
          co.payload.validatorParty == validator &&
            co.payload.svcParty == svc
        ),
        mkFilter(validatorLicenseCodegen.ValidatorLicense.COMPANION)(co =>
          co.payload.validator == validator && co.payload.svc == svc
        ),
        mkFilter(coinCodegen.ValidatorRight.COMPANION)(co =>
          co.payload.validator == validator &&
            co.payload.svc == svc
        ),
        mkFilter(coinCodegen.FeaturedAppRight.COMPANION)(co =>
          co.payload.svc == svc && co.payload.provider == validator
        ),
        mkFilter(ValidatorTraffic.COMPANION)(co =>
          co.payload.validator == validator &&
            co.payload.svc == svc
        ),
        mkFilter(ValidatorTrafficCreationIntent.COMPANION)(co => co.payload.validator == validator),
        mkFilter(ValidatorTopUpState.COMPANION)(co => co.payload.validator == validator),
        mkFilter(coinCodegen.Coin.COMPANION)(co =>
          co.payload.svc == svc &&
            co.payload.owner == validator
        ),
        mkFilter(coinCodegen.Coin.COMPANION)(co =>
          co.payload.svc == svc &&
            co.payload.owner == validator
        ),
        mkFilter(appManagerCodegen.AppConfiguration.COMPANION)(co =>
          co.payload.validatorOperator == validator
        ),
        mkFilter(appManagerCodegen.AppRelease.COMPANION)(co =>
          co.payload.validatorOperator == validator
        ),
        mkFilter(appManagerCodegen.RegisteredApp.COMPANION)(co =>
          co.payload.validatorOperator == validator
        ),
        mkFilter(appManagerCodegen.InstalledApp.COMPANION)(co =>
          co.payload.validatorOperator == validator
        ),
      ),
    )
  }

}
