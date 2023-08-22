package com.daml.network.validator.store.db

import cats.implicits.*
import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  validatorlicense as validatorLicenseCodegen,
}
import com.daml.network.codegen.java.cc.globaldomain as domainCodegen
import com.daml.network.codegen.java.cn.appmanager.store as appManagerCodegen
import com.daml.network.codegen.java.cn.wallet.install as walletCodegen
import com.daml.network.codegen.java.cn.wallet.topupstate as topupCodegen
import com.daml.network.environment.RetryProvider
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.db.AcsTables.AcsStoreRowTemplate
import com.daml.network.store.db.{AcsQueries, AcsTables, DbCNNodeAppStoreWithoutHistory}
import com.daml.network.util.{Contract, ContractWithState, TemplateJsonDecoder}
import com.daml.network.validator.config.ValidatorDomainConfig
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.store.db.ValidatorTables.ValidatorAcsStoreRowData
import com.daml.network.wallet.store.WalletStore
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import io.circe.Json
import slick.dbio
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbValidatorStore(
    override val key: ValidatorStore.Key,
    override protected[this] val domainConfig: ValidatorDomainConfig,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStoreWithoutHistory(
      storage = storage,
      tableName = DbValidatorStore.acsTableName,
      storeDescriptor = Json.obj(
        "version" -> Json.fromInt(1),
        "store" -> Json.fromString("DbValidatorStore"),
        "validatorParty" -> Json.fromString(key.validatorParty.toProtoPrimitive),
        "svcParty" -> Json.fromString(key.svcParty.toProtoPrimitive),
      ),
    )
    with ValidatorStore
    with AcsTables
    with AcsQueries {

  override val walletKey = WalletStore.Key(
    key.validatorParty,
    key.svcParty,
  )

  override lazy val acsContractFilter = ValidatorStore.contractFilter(key)

  import storage.DbStorageConverters.setParameterByteArray
  import multiDomainAcsStore.waitUntilAcsIngested

  private def storeId: Int = multiDomainAcsStore.storeId

  override def ingestionAcsInsert(createdEvent: CreatedEvent)(implicit
      tc: TraceContext
  ): Either[String, dbio.DBIO[_]] = {
    ValidatorAcsStoreRowData.fromCreatedEvent(createdEvent).map {
      case ValidatorAcsStoreRowData(
            contract,
            contractExpiresAt,
            userParty,
            userName,
            providerParty,
            validatorParty,
            trafficDomainId,
            appConfigurationVersion,
            appReleaseVersion,
            jsonHash,
          ) =>
        val contractId = contract.contractId.asInstanceOf[ContractId[Any]]
        val templateId = contract.identifier
        val createArguments = payloadJsonFromContract(contract.payload)
        val contractMetadataCreatedAt = Timestamp.assertFromInstant(contract.metadata.createdAt)
        val contractMetadataContractKeyHash =
          lengthLimited(contract.metadata.contractKeyHash.toStringUtf8)
        val contractMetadataDriverInternal = contract.metadata.driverMetadata.toByteArray
        val safeUserName = userName.map(lengthLimited)
        val safeAppReleaseVersion = appReleaseVersion.map(lengthLimited)
        val safeJsonHash = jsonHash.map(lengthLimited)
        sqlu"""
              insert into validator_acs_store(store_id, contract_id, template_id, create_arguments, contract_metadata_created_at,
                                        contract_metadata_contract_key_hash, contract_metadata_driver_internal,
                                        contract_expires_at, user_party, user_name, provider_party, validator_party,
                                        traffic_domain_id, app_configuration_version, app_release_version, json_hash)
              values ($storeId, $contractId, $templateId, $createArguments, $contractMetadataCreatedAt,
                      $contractMetadataContractKeyHash, $contractMetadataDriverInternal,
                      $contractExpiresAt, $userParty, $safeUserName,
                      $providerParty, $validatorParty, $trafficDomainId,
                      $appConfigurationVersion, $safeAppReleaseVersion, $safeJsonHash)
              on conflict do nothing
            """
    }
  }

  override def lookupInstallByParty(
      endUserParty: PartyId
  )(implicit tc: TraceContext): Future[Option[
    Contract[walletCodegen.WalletAppInstall.ContractId, walletCodegen.WalletAppInstall]
  ]] = for {
    _ <- defaultAcsDomainIdF
    row <- storage
      .querySingle(
        (selectFromAcsTable(DbValidatorStore.acsTableName) ++
          sql"""
              where store_id = $storeId
                and template_id = ${walletCodegen.WalletAppInstall.COMPANION.TEMPLATE_ID}
                and user_party = $endUserParty
              limit 1
          """).toActionBuilder.as[AcsStoreRowTemplate].headOption,
        "lookupInstallByParty",
      )
      .value
  } yield row.map(contractFromRow(walletCodegen.WalletAppInstall.COMPANION)(_))

  override def lookupInstallByName(
      endUserName: String
  )(implicit tc: TraceContext): Future[Option[
    Contract[walletCodegen.WalletAppInstall.ContractId, walletCodegen.WalletAppInstall]
  ]] = for {
    _ <- defaultAcsDomainIdF
    row <- storage
      .querySingle(
        (selectFromAcsTable(DbValidatorStore.acsTableName) ++
          sql"""
              where store_id = $storeId
                and template_id = ${walletCodegen.WalletAppInstall.COMPANION.TEMPLATE_ID}
                and user_name = ${lengthLimited(endUserName)}
              limit 1
          """).toActionBuilder.as[AcsStoreRowTemplate].headOption,
        "lookupInstallByName",
      )
      .value
  } yield row.map(contractFromRow(walletCodegen.WalletAppInstall.COMPANION)(_))

  override def lookupValidatorFeaturedAppRight()(implicit
      tc: TraceContext
  ): Future[
    Option[Contract[coinCodegen.FeaturedAppRight.ContractId, coinCodegen.FeaturedAppRight]]
  ] = for {
    _ <- defaultAcsDomainIdF
    row <- storage
      .querySingle(
        (selectFromAcsTable(DbValidatorStore.acsTableName) ++
          sql"""
              where store_id = $storeId
                and template_id = ${coinCodegen.FeaturedAppRight.COMPANION.TEMPLATE_ID}
                and provider_party = ${walletKey.validatorParty}
              limit 1
          """).toActionBuilder.as[AcsStoreRowTemplate].headOption,
        "lookupValidatorFeaturedAppRight",
      )
      .value
  } yield row.map(contractFromRow(coinCodegen.FeaturedAppRight.COMPANION)(_))

  override def lookupWalletInstallByNameWithOffset(
      endUserName: String
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[Contract[walletCodegen.WalletAppInstall.ContractId, walletCodegen.WalletAppInstall]]
  ]] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbValidatorStore.acsTableName,
            storeId,
            sql"""
            template_id = ${walletCodegen.WalletAppInstall.COMPANION.TEMPLATE_ID}
              and user_name = ${lengthLimited(endUserName)}
            """,
            sql"limit 1",
          ).toActionBuilder.as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupWalletInstallByNameWithOffset",
        )
        .getOrElse(throw offsetExpectedError())
    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(walletCodegen.WalletAppInstall.COMPANION)(_)),
    )
  }

  override def lookupValidatorLicenseWithOffset()(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[
      validatorLicenseCodegen.ValidatorLicense.ContractId,
      validatorLicenseCodegen.ValidatorLicense,
    ]]]
  ] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbValidatorStore.acsTableName,
            storeId,
            sql"""
            template_id = ${validatorLicenseCodegen.ValidatorLicense.COMPANION.TEMPLATE_ID}
              and validator_party = ${key.validatorParty}
            """,
            sql"limit 1",
          ).toActionBuilder.as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupValidatorLicenseWithOffset",
        )
        .getOrElse(throw offsetExpectedError())
    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(
        contractFromRow(validatorLicenseCodegen.ValidatorLicense.COMPANION)(_)
      ),
    )
  }

  override def lookupValidatorRightByPartyWithOffset(
      party: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[Contract[coinCodegen.ValidatorRight.ContractId, coinCodegen.ValidatorRight]]]
  ] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbValidatorStore.acsTableName,
            storeId,
            sql"""
            template_id = ${coinCodegen.ValidatorRight.COMPANION.TEMPLATE_ID}
              and user_party = $party
            """,
            sql"limit 1",
          ).toActionBuilder.as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupValidatorRightByPartyWithOffset",
        )
        .getOrElse(throw offsetExpectedError())
    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(coinCodegen.ValidatorRight.COMPANION)(_)),
    )
  }

  /** Lookup the validator-traffic contract for the given domain. */
  override def lookupValidatorTrafficWithOffset(
      domainId: DomainId
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[Contract[domainCodegen.ValidatorTraffic.ContractId, domainCodegen.ValidatorTraffic]]
    ]
  ] = waitUntilAcsIngested {
    for {
      // TODO(#4913): read from all domains in the global domain
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbValidatorStore.acsTableName,
            storeId,
            sql"""
            template_id = ${domainCodegen.ValidatorTraffic.COMPANION.TEMPLATE_ID}
              and traffic_domain_id = $domainId
            """,
            sql"limit 1",
          ).toActionBuilder.as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupValidatorTrafficWithOffset",
        )
        .getOrElse(throw offsetExpectedError())
    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(contractFromRow(domainCodegen.ValidatorTraffic.COMPANION)(_)),
    )
  }

  override def lookupValidatorTrafficCreationIntentWithOffset(
      domainId: DomainId
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[Contract[
        domainCodegen.ValidatorTrafficCreationIntent.ContractId,
        domainCodegen.ValidatorTrafficCreationIntent,
      ]]
    ]
  ] = waitUntilAcsIngested {
    for {
      // TODO(#4913): read from all domains in the global domain
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbValidatorStore.acsTableName,
            storeId,
            sql"""
            template_id = ${domainCodegen.ValidatorTrafficCreationIntent.COMPANION.TEMPLATE_ID}
              and traffic_domain_id = $domainId
            """,
            sql"limit 1",
          ).toActionBuilder.as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupValidatorTrafficCreationIntentWithOffset",
        )
        .getOrElse(throw offsetExpectedError())
    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(
        contractFromRow(domainCodegen.ValidatorTrafficCreationIntent.COMPANION)(_)
      ),
    )
  }

  override def lookupLatestAppConfiguration(
      provider: PartyId
  )(implicit tc: TraceContext): Future[Option[ContractWithState[
    appManagerCodegen.AppConfiguration.ContractId,
    appManagerCodegen.AppConfiguration,
  ]]] = waitUntilAcsIngested {
    for {
      row <- storage
        .querySingle(
          (selectFromAcsTable(DbValidatorStore.acsTableName) ++
            sql"""
            where store_id = $storeId
              and template_id = ${appManagerCodegen.AppConfiguration.COMPANION.TEMPLATE_ID}
              and provider_party = $provider
            order by app_configuration_version desc
            limit 1
        """).toActionBuilder.as[AcsStoreRowTemplate].headOption,
          "lookupLatestAppConfiguration",
        )
        .semiflatMap(
          multiDomainAcsStore.contractWithStateFromRow(
            appManagerCodegen.AppConfiguration.COMPANION
          )(_)
        )
        .value
    } yield row
  }

  override def lookupAppConfiguration(
      provider: PartyId,
      version: Long,
  )(implicit tc: TraceContext): Future[QueryResult[Option[ContractWithState[
    appManagerCodegen.AppConfiguration.ContractId,
    appManagerCodegen.AppConfiguration,
  ]]]] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbValidatorStore.acsTableName,
            storeId,
            sql"""
            template_id = ${appManagerCodegen.AppConfiguration.COMPANION.TEMPLATE_ID}
              and provider_party = $provider
              and app_configuration_version = $version
            """,
            sql"limit 1",
          ).toActionBuilder.as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupAppConfiguration",
        )
        .getOrElse(throw offsetExpectedError())
      contractWithState <-
        multiDomainAcsStore.contractWithStateFromOptRow(
          appManagerCodegen.AppConfiguration.COMPANION
        )(resultWithOffset.row)
    } yield QueryResult(resultWithOffset.offset, contractWithState)
  }

  override def lookupAppRelease(
      provider: PartyId,
      version: String,
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[ContractWithState[appManagerCodegen.AppRelease.ContractId, appManagerCodegen.AppRelease]]
  ]] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbValidatorStore.acsTableName,
            storeId,
            sql"""
            template_id = ${appManagerCodegen.AppRelease.COMPANION.TEMPLATE_ID}
              and provider_party = $provider
              and app_release_version = ${lengthLimited(version)}
            """,
            sql"limit 1",
          ).toActionBuilder.as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupAppRelease",
        )
        .getOrElse(throw offsetExpectedError())
      contractWithState <-
        multiDomainAcsStore.contractWithStateFromOptRow(
          appManagerCodegen.AppRelease.COMPANION
        )(resultWithOffset.row)
    } yield QueryResult(resultWithOffset.offset, contractWithState)
  }

  override def lookupRegisteredApp(
      provider: PartyId
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[
      ContractWithState[appManagerCodegen.RegisteredApp.ContractId, appManagerCodegen.RegisteredApp]
    ]
  ]] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbValidatorStore.acsTableName,
            storeId,
            sql"""
            template_id = ${appManagerCodegen.RegisteredApp.COMPANION.TEMPLATE_ID}
              and provider_party = $provider
            """,
            sql"limit 1",
          ).toActionBuilder.as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupRegisteredApp",
        )
        .getOrElse(throw offsetExpectedError())
      contractWithState <-
        multiDomainAcsStore.contractWithStateFromOptRow(
          appManagerCodegen.RegisteredApp.COMPANION
        )(resultWithOffset.row)
    } yield QueryResult(resultWithOffset.offset, contractWithState)
  }

  override def lookupInstalledApp(
      provider: PartyId
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[
      ContractWithState[appManagerCodegen.InstalledApp.ContractId, appManagerCodegen.InstalledApp]
    ]
  ]] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbValidatorStore.acsTableName,
            storeId,
            sql"""
            template_id = ${appManagerCodegen.InstalledApp.COMPANION.TEMPLATE_ID}
              and provider_party = $provider
            """,
            sql"limit 1",
          ).toActionBuilder.as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupInstalledApp",
        )
        .getOrElse(throw offsetExpectedError())
      contractWithState <-
        multiDomainAcsStore.contractWithStateFromOptRow(
          appManagerCodegen.InstalledApp.COMPANION
        )(resultWithOffset.row)
    } yield QueryResult(resultWithOffset.offset, contractWithState)
  }

  override protected def listApprovedReleaseConfigurations(
      provider: PartyId
  )(implicit traceContext: TraceContext): Future[
    Seq[ContractWithState[
      appManagerCodegen.ApprovedReleaseConfiguration.ContractId,
      appManagerCodegen.ApprovedReleaseConfiguration,
    ]]
  ] = waitUntilAcsIngested {
    for {
      rows <- storage
        .query(
          (selectFromAcsTable(DbValidatorStore.acsTableName) ++
            sql"""
            where store_id = $storeId
              and template_id = ${appManagerCodegen.ApprovedReleaseConfiguration.COMPANION.TEMPLATE_ID}
              and provider_party = $provider
        """).toActionBuilder.as[AcsStoreRowTemplate],
          "listApprovedReleaseConfigurations",
        )
      result <- rows.traverse(
        multiDomainAcsStore.contractWithStateFromRow(
          appManagerCodegen.ApprovedReleaseConfiguration.COMPANION
        )(_)
      )
    } yield result
  }

  override def lookupApprovedReleaseConfiguration(
      provider: PartyId,
      releaseConfigurationHash: Hash,
  )(implicit traceContext: TraceContext): Future[QueryResult[
    Option[ContractWithState[
      appManagerCodegen.ApprovedReleaseConfiguration.ContractId,
      appManagerCodegen.ApprovedReleaseConfiguration,
    ]]
  ]] = waitUntilAcsIngested {
    val jsonHash = lengthLimited(releaseConfigurationHash.toHexString)
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbValidatorStore.acsTableName,
            storeId,
            sql"""
            template_id = ${appManagerCodegen.ApprovedReleaseConfiguration.COMPANION.TEMPLATE_ID}
              and provider_party = $provider
              and json_hash = $jsonHash
            """,
            sql"limit 1",
          ).toActionBuilder.as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupApprovedReleaseConfiguration",
        )
        .getOrElse(throw offsetExpectedError())
      contractWithState <-
        multiDomainAcsStore.contractWithStateFromOptRow(
          appManagerCodegen.ApprovedReleaseConfiguration.COMPANION
        )(resultWithOffset.row)
    } yield QueryResult(resultWithOffset.offset, contractWithState)
  }

  override def lookupValidatorTopUpStateWithOffset(
      domainId: DomainId
  )(implicit traceContext: TraceContext): Future[QueryResult[Option[Contract[
    topupCodegen.ValidatorTopUpState.ContractId,
    topupCodegen.ValidatorTopUpState,
  ]]]] = waitUntilAcsIngested {
    for {
      // TODO(#4913): read from all domains in the global domain
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            DbValidatorStore.acsTableName,
            storeId,
            sql"""
            template_id = ${topupCodegen.ValidatorTopUpState.COMPANION.TEMPLATE_ID}
              and traffic_domain_id = $domainId
            """,
            sql"limit 1",
          ).toActionBuilder.as[AcsStoreRowTemplateWithOffset].headOption,
          "lookupValidatorTopUpStateWithOffset",
        )
        .getOrElse(throw offsetExpectedError())
    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(
        contractFromRow(
          topupCodegen.ValidatorTopUpState.COMPANION
        )(_)
      ),
    )
  }
}

object DbValidatorStore {
  val acsTableName: String = ValidatorTables.ValidatorAcsStore.baseTableRow.tableName
}
