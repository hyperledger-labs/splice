package com.daml.network.validator.store.memory

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  validatorlicense as validatorLicenseCodegen,
}
import com.daml.network.codegen.java.cn.appmanager.store as appManagerCodegen
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.codegen.java.cn.wallet.topupstate as topUpCodegen
import com.daml.network.environment.RetryProvider
import com.daml.network.store.{InMemoryCNNodeAppStoreWithoutHistory, PageLimit}
import com.daml.network.store.MultiDomainAcsStore.{ContractCompanion, QueryResult}
import com.daml.network.util.{Contract, ContractWithState}
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.wallet.store.WalletStore
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

class InMemoryValidatorStore(
    override val key: ValidatorStore.Key,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit override protected val ec: ExecutionContext)
    extends InMemoryCNNodeAppStoreWithoutHistory
    with ValidatorStore {
  import InMemoryValidatorStore.*

  override val walletKey = WalletStore.Key(
    key.validatorParty,
    key.svcParty,
  )

  override lazy val acsContractFilter = ValidatorStore.contractFilter(key)

  override def lookupInstallByParty(
      endUserParty: PartyId
  )(implicit tc: TraceContext): Future[Option[
    Contract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
  ]] = findOnlyContract(installCodegen.WalletAppInstall.COMPANION) {
    payload: installCodegen.WalletAppInstall =>
      payload.endUserParty == endUserParty.toProtoPrimitive
  }

  override def lookupInstallByName(
      endUserName: String
  )(implicit tc: TraceContext): Future[Option[
    Contract[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
  ]] = findOnlyContract(installCodegen.WalletAppInstall.COMPANION) {
    payload: installCodegen.WalletAppInstall =>
      payload.endUserName == endUserName
  }

  override def lookupValidatorFeaturedAppRight()(implicit
      tc: TraceContext
  ): Future[
    Option[Contract[coinCodegen.FeaturedAppRight.ContractId, coinCodegen.FeaturedAppRight]]
  ] = findOnlyContract(coinCodegen.FeaturedAppRight.COMPANION) {
    payload: coinCodegen.FeaturedAppRight =>
      payload.provider == walletKey.validatorParty.toProtoPrimitive
  }

  override def lookupWalletInstallByNameWithOffset(
      endUserName: String
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[
      ContractWithState[installCodegen.WalletAppInstall.ContractId, installCodegen.WalletAppInstall]
    ]
  ]] =
    multiDomainAcsStore.findContractWithOffset(installCodegen.WalletAppInstall.COMPANION)(
      (_: Contract[?, installCodegen.WalletAppInstall]).payload.endUserName == endUserName
    )

  override def lookupValidatorLicenseWithOffset()(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[
      validatorLicenseCodegen.ValidatorLicense.ContractId,
      validatorLicenseCodegen.ValidatorLicense,
    ]]]
  ] =
    multiDomainAcsStore.findContractWithOffset(
      validatorLicenseCodegen.ValidatorLicense.COMPANION
    ) { vl: Contract[?, validatorLicenseCodegen.ValidatorLicense] =>
      vl.payload.validator == key.validatorParty.toProtoPrimitive
    } map clearDomainFromLookup

  override def lookupValidatorRightByPartyWithOffset(
      party: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[ContractWithState[coinCodegen.ValidatorRight.ContractId, coinCodegen.ValidatorRight]]
    ]
  ] =
    multiDomainAcsStore.findContractWithOffset(coinCodegen.ValidatorRight.COMPANION)(
      (_: Contract[?, coinCodegen.ValidatorRight]).payload.user == party.toProtoPrimitive
    )

  override def lookupValidatorTopUpStateWithOffset(
      domainId: DomainId
  )(implicit traceContext: TraceContext): Future[
    QueryResult[
      Option[
        Contract[topUpCodegen.ValidatorTopUpState.ContractId, topUpCodegen.ValidatorTopUpState]
      ]
    ]
  ] =
    multiDomainAcsStore.findContractOnDomainWithOffset(topUpCodegen.ValidatorTopUpState.COMPANION)(
      domainId,
      intent => intent.payload.domainId == domainId.toProtoPrimitive,
    )

  override def lookupLatestAppConfiguration(
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

  override def lookupAppConfiguration(
      provider: PartyId,
      version: Long,
  )(implicit tc: TraceContext): Future[QueryResult[Option[ContractWithState[
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

  override def lookupAppRelease(
      provider: PartyId,
      version: String,
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[ContractWithState[appManagerCodegen.AppRelease.ContractId, appManagerCodegen.AppRelease]]
  ]] =
    multiDomainAcsStore
      .findContractWithOffset(appManagerCodegen.AppRelease.COMPANION)(
        (c: Contract[appManagerCodegen.AppRelease.ContractId, appManagerCodegen.AppRelease]) =>
          c.payload.provider == provider.toProtoPrimitive && c.payload.version == version
      )

  override def lookupRegisteredApp(
      provider: PartyId
  )(implicit tc: TraceContext): Future[QueryResult[
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

  override def lookupInstalledApp(
      provider: PartyId
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[
      ContractWithState[appManagerCodegen.InstalledApp.ContractId, appManagerCodegen.InstalledApp]
    ]
  ]] =
    multiDomainAcsStore
      .findContractWithOffset(appManagerCodegen.InstalledApp.COMPANION)(
        (c: Contract[appManagerCodegen.InstalledApp.ContractId, appManagerCodegen.InstalledApp]) =>
          c.payload.provider == provider.toProtoPrimitive
      )

  override def listApprovedReleaseConfigurations(provider: PartyId)(implicit
      traceContext: TraceContext
  ): Future[Seq[
    ContractWithState[
      appManagerCodegen.ApprovedReleaseConfiguration.ContractId,
      appManagerCodegen.ApprovedReleaseConfiguration,
    ]
  ]] =
    multiDomainAcsStore.filterContracts(
      appManagerCodegen.ApprovedReleaseConfiguration.COMPANION,
      (c: Contract[_, appManagerCodegen.ApprovedReleaseConfiguration]) =>
        c.payload.provider == provider.toProtoPrimitive,
    )

  override def lookupApprovedReleaseConfiguration(
      provider: PartyId,
      releaseConfigurationHash: Hash,
  )(implicit traceContext: TraceContext): Future[QueryResult[Option[ContractWithState[
    appManagerCodegen.ApprovedReleaseConfiguration.ContractId,
    appManagerCodegen.ApprovedReleaseConfiguration,
  ]]]] =
    multiDomainAcsStore.findContractWithOffset(
      appManagerCodegen.ApprovedReleaseConfiguration.COMPANION
    )(
      (c: Contract[
        appManagerCodegen.ApprovedReleaseConfiguration.ContractId,
        appManagerCodegen.ApprovedReleaseConfiguration,
      ]) =>
        c.payload.provider == provider.toProtoPrimitive &&
          c.payload.jsonHash == releaseConfigurationHash.toHexString
    )

  private[this] def findOnlyContract[C, TCid <: ContractId[_], T](companion: C)(
      filter: T => Boolean
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Option[Contract[TCid, T]]] =
    for {
      maybeContract <- multiDomainAcsStore.filterContracts(
        companion,
        (c: Contract[?, T]) => filter(c.payload),
        PageLimit(1),
      )
    } yield maybeContract.headOption map (_.contract)
}

private[memory] object InMemoryValidatorStore {
  private def clearDomainFromLookup[I, A](
      fa: QueryResult[Option[Contract.Has[I, A]]]
  ): QueryResult[Option[Contract[I, A]]] =
    fa map (_ map (_.contract))
}
