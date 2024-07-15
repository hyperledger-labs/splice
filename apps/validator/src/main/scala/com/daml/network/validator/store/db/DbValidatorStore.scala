// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.validator.store.db

import cats.implicits.*
import com.daml.network.codegen.java.splice.{
  amulet as amuletCodegen,
  validatorlicense as validatorLicenseCodegen,
}
import com.daml.network.codegen.java.splice.appmanager.store as appManagerCodegen
import com.daml.network.codegen.java.splice.appmanager.store.AppConfiguration
import com.daml.network.codegen.java.splice.wallet.{
  install as walletCodegen,
  topupstate as topupCodegen,
}
import com.daml.network.environment.RetryProvider
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.store.db.DbMultiDomainAcsStore.StoreDescriptor
import com.daml.network.store.db.{AcsQueries, AcsTables, DbAppStore}
import com.daml.network.store.{Limit, LimitHelpers}
import com.daml.network.util.{Contract, ContractWithState, QualifiedName, TemplateJsonDecoder}
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.wallet.store.WalletStore
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbValidatorStore(
    override val key: ValidatorStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbAppStore(
      storage = storage,
      acsTableName = ValidatorTables.acsTableName,
      // Any change in the store descriptor will lead to previously deployed applications
      // forgetting all persisted data once they upgrade to the new version.
      storeDescriptor = StoreDescriptor(
        version = 1,
        name = "DbValidatorStore",
        party = key.validatorParty,
        participant = participantId,
        key = Map(
          "validatorParty" -> key.validatorParty.toProtoPrimitive,
          "dsoParty" -> key.dsoParty.toProtoPrimitive,
        ),
      ),
      domainMigrationInfo = domainMigrationInfo,
      participantId = participantId,
      enableissue12777Workaround = false,
    )
    with ValidatorStore
    with AcsTables
    with AcsQueries
    with LimitHelpers {

  override val walletKey = WalletStore.Key(
    key.validatorParty,
    key.dsoParty,
  )

  override lazy val acsContractFilter = ValidatorStore.contractFilter(key, domainMigrationId)

  import multiDomainAcsStore.waitUntilAcsIngested

  private def storeId: Int = multiDomainAcsStore.storeId
  override def domainMigrationId: Long = domainMigrationInfo.currentMigrationId

  override def lookupInstallByParty(
      endUserParty: PartyId
  )(implicit tc: TraceContext): Future[Option[
    Contract[walletCodegen.WalletAppInstall.ContractId, walletCodegen.WalletAppInstall]
  ]] = for {
    row <- storage
      .querySingle(
        selectFromAcsTable(
          ValidatorTables.acsTableName,
          storeId,
          domainMigrationId,
          where = sql"""template_id_qualified_name = ${QualifiedName(
              walletCodegen.WalletAppInstall.COMPANION.TEMPLATE_ID
            )} and user_party = $endUserParty""",
          orderLimit = sql"limit 1",
        ).headOption,
        "lookupInstallByParty",
      )
      .value
  } yield row.map(contractFromRow(walletCodegen.WalletAppInstall.COMPANION)(_))

  override def lookupInstallByName(
      endUserName: String
  )(implicit tc: TraceContext): Future[Option[
    Contract[walletCodegen.WalletAppInstall.ContractId, walletCodegen.WalletAppInstall]
  ]] = for {
    row <- storage
      .querySingle(
        selectFromAcsTable(
          ValidatorTables.acsTableName,
          storeId,
          domainMigrationId,
          where = sql"""template_id_qualified_name = ${QualifiedName(
              walletCodegen.WalletAppInstall.COMPANION.TEMPLATE_ID
            )} and user_name = ${lengthLimited(endUserName)}""",
          orderLimit = sql"limit 1",
        ).headOption,
        "lookupInstallByName",
      )
      .value
  } yield row.map(contractFromRow(walletCodegen.WalletAppInstall.COMPANION)(_))

  override def lookupValidatorFeaturedAppRight()(implicit
      tc: TraceContext
  ): Future[
    Option[Contract[amuletCodegen.FeaturedAppRight.ContractId, amuletCodegen.FeaturedAppRight]]
  ] = for {
    row <- storage
      .querySingle(
        selectFromAcsTable(
          ValidatorTables.acsTableName,
          storeId,
          domainMigrationId,
          where = sql"""template_id_qualified_name = ${QualifiedName(
              amuletCodegen.FeaturedAppRight.COMPANION.TEMPLATE_ID
            )} and provider_party = ${walletKey.validatorParty}""",
          orderLimit = sql"limit 1",
        ).headOption,
        "lookupValidatorFeaturedAppRight",
      )
      .value
  } yield row.map(contractFromRow(amuletCodegen.FeaturedAppRight.COMPANION)(_))

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
          selectFromAcsTableWithStateAndOffset(
            ValidatorTables.acsTableName,
            storeId,
            domainMigrationId,
            sql"""
            template_id_qualified_name = ${QualifiedName(
                walletCodegen.WalletAppInstall.COMPANION.TEMPLATE_ID
              )}
              and user_name = ${lengthLimited(endUserName)}
            """,
            sql"limit 1",
          ).headOption,
          "lookupWalletInstallByNameWithOffset",
        )
        .getOrElse(throw offsetExpectedError())
    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(
        contractWithStateFromRow(walletCodegen.WalletAppInstall.COMPANION)(_)
      ),
    )
  }

  override def lookupValidatorLicenseWithOffset()(implicit tc: TraceContext): Future[
    QueryResult[Option[ContractWithState[
      validatorLicenseCodegen.ValidatorLicense.ContractId,
      validatorLicenseCodegen.ValidatorLicense,
    ]]]
  ] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            ValidatorTables.acsTableName,
            storeId,
            domainMigrationId,
            sql"""
            template_id_qualified_name = ${QualifiedName(
                validatorLicenseCodegen.ValidatorLicense.COMPANION.TEMPLATE_ID
              )}
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
        contractWithStateFromRow(validatorLicenseCodegen.ValidatorLicense.COMPANION)(_)
      ),
    )
  }

  override def lookupValidatorRightByPartyWithOffset(
      party: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[
        ContractWithState[amuletCodegen.ValidatorRight.ContractId, amuletCodegen.ValidatorRight]
      ]
    ]
  ] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            ValidatorTables.acsTableName,
            storeId,
            domainMigrationId,
            sql"""
            template_id_qualified_name = ${QualifiedName(
                amuletCodegen.ValidatorRight.COMPANION.TEMPLATE_ID
              )}
              and user_party = $party
            """,
            sql"limit 1",
          ).headOption,
          "lookupValidatorRightByPartyWithOffset",
        )
        .getOrElse(throw offsetExpectedError())
    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(
        contractWithStateFromRow(amuletCodegen.ValidatorRight.COMPANION)(_)
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
          selectFromAcsTableWithState(
            ValidatorTables.acsTableName,
            storeId,
            domainMigrationId,
            where = sql"""
              template_id_qualified_name = ${QualifiedName(
                appManagerCodegen.AppConfiguration.COMPANION.TEMPLATE_ID
              )}
              and provider_party = $provider
              """,
            orderLimit = sql"""order by app_configuration_version desc limit 1""",
          ).headOption,
          "lookupLatestAppConfiguration",
        )
        .value
      result = row.map(
        contractWithStateFromRow(
          appManagerCodegen.AppConfiguration.COMPANION
        )(_)
      )
    } yield result
  }

  override def lookupLatestAppConfigurationByName(name: String)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[AppConfiguration.ContractId, AppConfiguration]]] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            selectFromAcsTableWithState(
              ValidatorTables.acsTableName,
              storeId,
              domainMigrationId,
              where = sql"""
                template_id_qualified_name = ${QualifiedName(
                  appManagerCodegen.AppConfiguration.TEMPLATE_ID
                )}
                and app_configuration_name = ${lengthLimited(name)}
                """,
              orderLimit = sql"""order by app_configuration_version desc
                                 limit 1""",
            ).headOption,
            "lookupLatestAppConfigurationByName",
          )
          .value
        result = row.map(
          contractWithStateFromRow(
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
            domainMigrationId,
            where = sql"""template_id_qualified_name = ${QualifiedName(
                appManagerCodegen.AppConfiguration.COMPANION.TEMPLATE_ID
              )}
              and provider_party = $provider
              and app_configuration_version = $version
            """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupAppConfiguration",
        )
        .getOrElse(throw offsetExpectedError())
      contractWithState = resultWithOffset.row.map(
        contractWithStateFromRow(
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
            domainMigrationId,
            where = sql"""template_id_qualified_name = ${QualifiedName(
                appManagerCodegen.AppRelease.COMPANION.TEMPLATE_ID
              )}
              and provider_party = $provider
              and app_release_version = ${lengthLimited(version)}
            """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupAppRelease",
        )
        .getOrElse(throw offsetExpectedError())
      contractWithState = resultWithOffset.row.map(
        contractWithStateFromRow(
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
            domainMigrationId,
            where = sql"""template_id_qualified_name = ${QualifiedName(
                appManagerCodegen.RegisteredApp.COMPANION.TEMPLATE_ID
              )}
              and provider_party = $provider
            """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupRegisteredApp",
        )
        .getOrElse(throw offsetExpectedError())
      contractWithState = resultWithOffset.row.map(
        contractWithStateFromRow(
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
            domainMigrationId,
            where = sql"""template_id_qualified_name = ${QualifiedName(
                appManagerCodegen.InstalledApp.COMPANION.TEMPLATE_ID
              )}
              and provider_party = $provider
            """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupInstalledApp",
        )
        .getOrElse(throw offsetExpectedError())
      contractWithState = resultWithOffset.row.map(
        contractWithStateFromRow(
          appManagerCodegen.InstalledApp.COMPANION
        )(_)
      )
    } yield QueryResult(resultWithOffset.offset, contractWithState)
  }

  override protected def listApprovedReleaseConfigurations(
      provider: PartyId,
      limit: Limit = Limit.DefaultLimit,
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
            domainMigrationId,
            where = sql"""
              template_id_qualified_name = ${QualifiedName(
                appManagerCodegen.ApprovedReleaseConfiguration.COMPANION.TEMPLATE_ID
              )}
              and provider_party = $provider""",
            orderLimit = sql"limit ${sqlLimit(limit)}",
          ),
          "listApprovedReleaseConfigurations",
        )
      result = applyLimit("listApprovedReleaseConfigurations", limit, rows).map(
        contractWithStateFromRow(
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
            domainMigrationId,
            where = sql"""template_id_qualified_name = ${QualifiedName(
                appManagerCodegen.ApprovedReleaseConfiguration.COMPANION.TEMPLATE_ID
              )}
              and provider_party = $provider
              and json_hash = $jsonHash
            """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupApprovedReleaseConfiguration",
        )
        .getOrElse(throw offsetExpectedError())
      contractWithState = resultWithOffset.row.map(
        contractWithStateFromRow(
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
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            ValidatorTables.acsTableName,
            storeId,
            domainMigrationId,
            sql"""
            template_id_qualified_name = ${QualifiedName(
                topupCodegen.ValidatorTopUpState.COMPANION.TEMPLATE_ID
              )}
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
