package com.daml.network.validator.store.db

import cats.implicits.*
import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.lf.data.Time.Timestamp
import com.daml.network.codegen.java.cc.{
  coin as coinCodegen,
  validatorlicense as validatorLicenseCodegen,
}
import com.daml.network.codegen.java.cn.appmanager.store as appManagerCodegen
import com.daml.network.codegen.java.cn.wallet.install as walletCodegen
import com.daml.network.codegen.java.cn.wallet.topupstate as topupCodegen
import com.daml.network.environment.RetryProvider
import com.daml.network.store.ConfiguredDefaultDomain
import com.daml.network.store.MultiDomainAcsStore.ContractState.Assigned
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.db.AcsQueries.SelectFromAcsTableResult
import com.daml.network.store.db.AcsTables.ContractStateRowData
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
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbValidatorStore(
    override val key: ValidatorStore.Key,
    domainConfig: ValidatorDomainConfig,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbCNNodeAppStoreWithoutHistory(
      storage = storage,
      tableName = ValidatorTables.acsTableName,
      storeDescriptor = Json.obj(
        "version" -> Json.fromInt(1),
        "store" -> Json.fromString("DbValidatorStore"),
        "validatorParty" -> Json.fromString(key.validatorParty.toProtoPrimitive),
        "svcParty" -> Json.fromString(key.svcParty.toProtoPrimitive),
      ),
    )
    with ValidatorStore
    with ConfiguredDefaultDomain
    with AcsTables
    with AcsQueries {

  override final def defaultAcsDomain = domainConfig.global.alias

  override val walletKey = WalletStore.Key(
    key.validatorParty,
    key.svcParty,
  )

  override lazy val acsContractFilter = ValidatorStore.contractFilter(key)

  import storage.DbStorageConverters.setParameterByteArray
  import multiDomainAcsStore.waitUntilAcsIngested

  private def storeId: Int = multiDomainAcsStore.storeId

  override def ingestionAcsInsert(
      createdEvent: CreatedEvent,
      contractState: ContractStateRowData,
  )(implicit
      tc: TraceContext
  ) = {
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
                                        contract_metadata_contract_key_hash, contract_metadata_driver_internal, contract_expires_at,
                                        assigned_domain, reassignment_counter, reassignment_target_domain,
                                        reassignment_source_domain, reassignment_submitter, reassignment_unassign_id,
                                        user_party, user_name, provider_party, validator_party,
                                        traffic_domain_id, app_configuration_version, app_release_version, json_hash)
              values ($storeId, $contractId, $templateId, $createArguments, $contractMetadataCreatedAt,
                      $contractMetadataContractKeyHash, $contractMetadataDriverInternal, $contractExpiresAt,
                      ${contractState.assignedDomain}, ${contractState.reassignmentCounter}, ${contractState.reassignmentTargetDomain},
                      ${contractState.reassignmentSourceDomain}, ${contractState.reassignmentSubmitter}, ${contractState.reassignmentUnassignId},
                      $userParty, $safeUserName, $providerParty, $validatorParty, $trafficDomainId,
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
        (selectFromAcsTable(ValidatorTables.acsTableName) ++
          sql"""
              where store_id = $storeId
                and template_id = ${walletCodegen.WalletAppInstall.COMPANION.TEMPLATE_ID}
                and user_party = $endUserParty
              limit 1
          """).toActionBuilder.as[SelectFromAcsTableResult].headOption,
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
        (selectFromAcsTable(ValidatorTables.acsTableName) ++
          sql"""
              where store_id = $storeId
                and template_id = ${walletCodegen.WalletAppInstall.COMPANION.TEMPLATE_ID}
                and user_name = ${lengthLimited(endUserName)}
              limit 1
          """).toActionBuilder.as[SelectFromAcsTableResult].headOption,
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
        (selectFromAcsTable(ValidatorTables.acsTableName) ++
          sql"""
              where store_id = $storeId
                and template_id = ${coinCodegen.FeaturedAppRight.COMPANION.TEMPLATE_ID}
                and provider_party = ${walletKey.validatorParty}
              limit 1
          """).toActionBuilder.as[SelectFromAcsTableResult].headOption,
        "lookupValidatorFeaturedAppRight",
      )
      .value
  } yield row.map(contractFromRow(coinCodegen.FeaturedAppRight.COMPANION)(_))

  override def lookupWalletInstallByNameWithOffset(
      endUserName: String
  )(implicit tc: TraceContext): Future[QueryResult[
    Option[
      ContractWithState[walletCodegen.WalletAppInstall.ContractId, walletCodegen.WalletAppInstall]
    ]
  ]] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            ValidatorTables.acsTableName,
            storeId,
            sql"""
            template_id = ${walletCodegen.WalletAppInstall.COMPANION.TEMPLATE_ID}
              and user_name = ${lengthLimited(endUserName)}
            """,
            sql"limit 1",
          ).headOption,
          "lookupWalletInstallByNameWithOffset",
        )
        .getOrElse(throw offsetExpectedError())
      domain <- defaultAcsDomainIdF // TODO (#5314) use state from query
    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map { r =>
        ContractWithState(
          contractFromRow(walletCodegen.WalletAppInstall.COMPANION)(r),
          Assigned(domain),
        )

      },
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
            ValidatorTables.acsTableName,
            storeId,
            sql"""
            template_id = ${validatorLicenseCodegen.ValidatorLicense.COMPANION.TEMPLATE_ID}
              and validator_party = ${key.validatorParty}
            """,
            sql"limit 1",
          ).headOption,
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
    QueryResult[
      Option[ContractWithState[coinCodegen.ValidatorRight.ContractId, coinCodegen.ValidatorRight]]
    ]
  ] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            ValidatorTables.acsTableName,
            storeId,
            sql"""
            template_id = ${coinCodegen.ValidatorRight.COMPANION.TEMPLATE_ID}
              and user_party = $party
            """,
            sql"limit 1",
          ).headOption,
          "lookupValidatorRightByPartyWithOffset",
        )
        .getOrElse(throw offsetExpectedError())
      domain <- defaultAcsDomainIdF // TODO (#5314) use state from query
    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map { r =>
        ContractWithState(
          contractFromRow(coinCodegen.ValidatorRight.COMPANION)(r),
          Assigned(domain),
        )
      },
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
          selectFromAcsTableWithState(
            ValidatorTables.acsTableName,
            storeId,
            where = sql"""
              template_id = ${appManagerCodegen.AppConfiguration.COMPANION.TEMPLATE_ID}
              and provider_party = $provider
              """,
            orderLimit = sql"""order by app_configuration_version desc limit 1""",
          ).headOption,
          "lookupLatestAppConfiguration",
        )
        .value
      result = row.map(
        multiDomainAcsStore.contractWithStateFromRow(
          appManagerCodegen.AppConfiguration.COMPANION
        )(_)
      )
    } yield result
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
          selectFromAcsTableWithStateAndOffset(
            ValidatorTables.acsTableName,
            storeId,
            where = sql"""template_id = ${appManagerCodegen.AppConfiguration.COMPANION.TEMPLATE_ID}
              and provider_party = $provider
              and app_configuration_version = $version
            """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupAppConfiguration",
        )
        .getOrElse(throw offsetExpectedError())
      contractWithState = resultWithOffset.row.map(
        multiDomainAcsStore.contractWithStateFromRow(
          appManagerCodegen.AppConfiguration.COMPANION
        )(_)
      )
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
          selectFromAcsTableWithStateAndOffset(
            ValidatorTables.acsTableName,
            storeId,
            where = sql"""template_id = ${appManagerCodegen.AppRelease.COMPANION.TEMPLATE_ID}
              and provider_party = $provider
              and app_release_version = ${lengthLimited(version)}
            """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupAppRelease",
        )
        .getOrElse(throw offsetExpectedError())
      contractWithState = resultWithOffset.row.map(
        multiDomainAcsStore.contractWithStateFromRow(
          appManagerCodegen.AppRelease.COMPANION
        )(_)
      )
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
          selectFromAcsTableWithStateAndOffset(
            ValidatorTables.acsTableName,
            storeId,
            where = sql"""template_id = ${appManagerCodegen.RegisteredApp.COMPANION.TEMPLATE_ID}
              and provider_party = $provider
            """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupRegisteredApp",
        )
        .getOrElse(throw offsetExpectedError())
      contractWithState = resultWithOffset.row.map(
        multiDomainAcsStore.contractWithStateFromRow(
          appManagerCodegen.RegisteredApp.COMPANION
        )(_)
      )
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
          selectFromAcsTableWithStateAndOffset(
            ValidatorTables.acsTableName,
            storeId,
            where = sql"""template_id = ${appManagerCodegen.InstalledApp.COMPANION.TEMPLATE_ID}
              and provider_party = $provider
            """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupInstalledApp",
        )
        .getOrElse(throw offsetExpectedError())
      contractWithState = resultWithOffset.row.map(
        multiDomainAcsStore.contractWithStateFromRow(
          appManagerCodegen.InstalledApp.COMPANION
        )(_)
      )
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
          selectFromAcsTableWithState(
            ValidatorTables.acsTableName,
            storeId,
            where = sql"""
              template_id = ${appManagerCodegen.ApprovedReleaseConfiguration.COMPANION.TEMPLATE_ID}
              and provider_party = $provider""",
          ),
          "listApprovedReleaseConfigurations",
        )
      result = rows.map(
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
          selectFromAcsTableWithStateAndOffset(
            ValidatorTables.acsTableName,
            storeId,
            where =
              sql"""template_id = ${appManagerCodegen.ApprovedReleaseConfiguration.COMPANION.TEMPLATE_ID}
              and provider_party = $provider
              and json_hash = $jsonHash
            """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupApprovedReleaseConfiguration",
        )
        .getOrElse(throw offsetExpectedError())
      contractWithState = resultWithOffset.row.map(
        multiDomainAcsStore.contractWithStateFromRow(
          appManagerCodegen.ApprovedReleaseConfiguration.COMPANION
        )(_)
      )
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
            ValidatorTables.acsTableName,
            storeId,
            sql"""
            template_id = ${topupCodegen.ValidatorTopUpState.COMPANION.TEMPLATE_ID}
              and traffic_domain_id = $domainId
            """,
            sql"limit 1",
          ).headOption,
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
