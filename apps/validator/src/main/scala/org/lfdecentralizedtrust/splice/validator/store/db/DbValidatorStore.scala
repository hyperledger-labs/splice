// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store.db

import com.digitalasset.canton.tracing.TraceContext

import org.lfdecentralizedtrust.splice.store.db.DbMultiDomainAcsStore

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  ExternalPartySetupProposal,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.{
  install as walletCodegen,
  topupstate as topupCodegen,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  validatorlicense as validatorLicenseCodegen,
}
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.store.db.StoreDescriptor
import org.lfdecentralizedtrust.splice.store.db.{
  AcsInterfaceViewRowData,
  AcsQueries,
  AcsTables,
  DbAppStore,
}
import org.lfdecentralizedtrust.splice.store.{Limit, LimitHelpers, PageLimit}
import org.lfdecentralizedtrust.splice.util.{Contract, ContractWithState, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.validator.store.ValidatorStore
import org.lfdecentralizedtrust.splice.wallet.store.WalletStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import org.lfdecentralizedtrust.splice.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.AcsStoreId
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

object DbValidatorStore {

  /** Looks up the store ids, and creates the store. */
  def create(
      key: ValidatorStore.Key,
      storage: DbStorage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      domainMigrationId: Long,
      participantId: ParticipantId,
      ingestionConfig: IngestionConfig,
      acsStoreDescriptorUserVersion: Option[Long] = None,
      defaultLimit: Limit,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
      tc: TraceContext,
  ): Future[DbValidatorStore] =
    DbMultiDomainAcsStore
      .lookupStoreIds(
        storage,
        acsStoreDescriptor(key, participantId, acsStoreDescriptorUserVersion),
        None,
      )
      .map { storeIds =>
        new DbValidatorStore(
          key = key,
          storage = storage,
          loggerFactory = loggerFactory,
          retryProvider = retryProvider,
          domainMigrationId = domainMigrationId,
          participantId = participantId,
          ingestionConfig = ingestionConfig,
          storeIds,
          acsStoreDescriptorUserVersion = acsStoreDescriptorUserVersion,
          defaultLimit = defaultLimit,
        )
      }

  // Any change in the store descriptor will lead to previously deployed applications
  // forgetting all persisted data once they upgrade to the new version.
  // WARNING: Reinitializing the acs store is a very expensive operation, as it currently fetches the full
  // unfiltered ACS from the participant, irrespective of the filter defined by `acsContractFilter`.
  // This may lead to the entire app being unavailable or not working properly until the full ACS has been ingested.
  // Do not modify any part of the store descriptor unless you are sure that the resulting downtime is acceptable.
  // If you do modify it, make sure to very clearly document in the release notes that there will be planned downtime,
  // and notify the person coordinating the deployment.
  def acsStoreDescriptor(
      key: ValidatorStore.Key,
      participantId: ParticipantId,
      acsStoreDescriptorUserVersion: Option[Long],
  ): StoreDescriptor =
    StoreDescriptor(
      version = 2,
      name = "DbValidatorStore",
      party = key.validatorParty,
      participant = participantId,
      key = Map(
        "validatorParty" -> key.validatorParty.toProtoPrimitive,
        "dsoParty" -> key.dsoParty.toProtoPrimitive,
      ),
      userVersion = acsStoreDescriptorUserVersion,
    )
}

class DbValidatorStore private (
    override val key: ValidatorStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    val domainMigrationId: Long,
    participantId: ParticipantId,
    ingestionConfig: IngestionConfig,
    storeIds: DbMultiDomainAcsStore.StoreIds,
    acsStoreDescriptorUserVersion: Option[Long],
    override val defaultLimit: Limit,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbAppStore(
      storage = storage,
      acsTableName = ValidatorTables.acsTableName,
      interfaceViewsTableNameOpt = None,
      acsStoreDescriptor =
        DbValidatorStore.acsStoreDescriptor(key, participantId, acsStoreDescriptorUserVersion),
      storeIds = storeIds,
      migrationId = domainMigrationId,
      ingestionConfig = ingestionConfig,
    )
    with ValidatorStore
    with AcsTables
    with AcsQueries
    with LimitHelpers {

  override val walletKey = WalletStore.Key(
    key.validatorParty,
    key.dsoParty,
  )

  override lazy val acsContractFilter
      : org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractFilter[
        org.lfdecentralizedtrust.splice.validator.store.db.ValidatorTables.ValidatorAcsStoreRowData,
        AcsInterfaceViewRowData.NoInterfacesIngested,
      ] = ValidatorStore.contractFilter(key, domainMigrationId)

  import multiDomainAcsStore.waitUntilAcsIngested
  import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture

  private def acsStoreId: AcsStoreId = multiDomainAcsStore.acsStoreId

  override def lookupInstallByParty(
      endUserParty: PartyId
  )(implicit tc: TraceContext): Future[Option[
    Contract[walletCodegen.WalletAppInstall.ContractId, walletCodegen.WalletAppInstall]
  ]] = for {
    row <- storage
      .querySingle(
        selectFromAcsTable(
          ValidatorTables.acsTableName,
          acsStoreId,
          domainMigrationId,
          walletCodegen.WalletAppInstall.COMPANION,
          where = sql"""user_party = $endUserParty""",
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
          acsStoreId,
          domainMigrationId,
          walletCodegen.WalletAppInstall.COMPANION,
          where = sql"""user_name = ${lengthLimited(endUserName)}""",
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
          acsStoreId,
          domainMigrationId,
          amuletCodegen.FeaturedAppRight.COMPANION,
          where = sql"""provider_party = ${walletKey.validatorParty}""",
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
            acsStoreId,
            domainMigrationId,
            walletCodegen.WalletAppInstall.COMPANION,
            sql"""user_name = ${lengthLimited(endUserName)}
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

  override def listExpiringTransferPreapprovals(
      renewalDuration: NonNegativeFiniteDuration
  ): ListExpiredContracts[TransferPreapproval.ContractId, TransferPreapproval] = {
    (now: CantonTimestamp, limit: PageLimit) => implicit traceContext =>
      waitUntilAcsIngested {
        for {
          result <- storage
            .query(
              selectFromAcsTableWithState(
                ValidatorTables.acsTableName,
                acsStoreId,
                domainMigrationId,
                TransferPreapproval.COMPANION,
                additionalWhere = sql"""and contract_expires_at > $now
                    and contract_expires_at < ${now.plus(renewalDuration.asJava)}
              """,
              ),
              "listExpiringTransferPreapprovals",
            )
          limited = applyLimit("listExpiringTransferPreapprovals", limit, result)
        } yield limited.map(assignedContractFromRow(TransferPreapproval.COMPANION)(_))
      }
  }

  override def lookupExternalPartySetupProposalByUserPartyWithOffset(
      partyId: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[ContractWithState[ExternalPartySetupProposal.ContractId, ExternalPartySetupProposal]]
    ]
  ] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            ValidatorTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            ExternalPartySetupProposal.COMPANION,
            where = sql"""user_party = $partyId""",
            orderLimit = sql"""
                limit 1
            """,
          ).headOption,
          "lookupExternalPartySetupProposalUser",
        )
        .getOrElse(throw offsetExpectedError())

    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(
        contractWithStateFromRow(ExternalPartySetupProposal.COMPANION)(_)
      ),
    )
  }

  override def lookupTransferPreapprovalByReceiverPartyWithOffset(
      partyId: PartyId
  )(implicit tc: TraceContext): Future[
    QueryResult[Option[ContractWithState[TransferPreapproval.ContractId, TransferPreapproval]]]
  ] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            ValidatorTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            TransferPreapproval.COMPANION,
            where = sql"""user_party = $partyId""",
            orderLimit = sql"""limit 1""",
          ).headOption,
          "lookupTransferPreapprovalReceiver",
        )
        .getOrElse(throw offsetExpectedError())

    } yield QueryResult(
      resultWithOffset.offset,
      resultWithOffset.row.map(
        contractWithStateFromRow(TransferPreapproval.COMPANION)(_)
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
            acsStoreId,
            domainMigrationId,
            validatorLicenseCodegen.ValidatorLicense.COMPANION,
            where = sql"""validator_party = ${key.validatorParty}""",
            orderLimit = sql"limit 1",
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
            acsStoreId,
            domainMigrationId,
            amuletCodegen.ValidatorRight.COMPANION,
            where = sql"""user_party = $party""",
            orderLimit = sql"limit 1",
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

  override def lookupValidatorTopUpStateWithOffset(
      synchronizerId: SynchronizerId
  )(implicit traceContext: TraceContext): Future[QueryResult[Option[Contract[
    topupCodegen.ValidatorTopUpState.ContractId,
    topupCodegen.ValidatorTopUpState,
  ]]]] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithOffset(
            ValidatorTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            topupCodegen.ValidatorTopUpState.COMPANION,
            where = sql"""traffic_domain_id = $synchronizerId""",
            orderLimit = sql"limit 1",
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
