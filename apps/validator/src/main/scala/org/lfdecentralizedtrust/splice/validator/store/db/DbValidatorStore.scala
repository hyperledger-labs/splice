// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store.db

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
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.store.db.StoreDescriptor
import org.lfdecentralizedtrust.splice.store.db.{
  AcsInterfaceViewRowData,
  AcsQueries,
  AcsTables,
  DbAppStore,
}
import org.lfdecentralizedtrust.splice.store.{LimitHelpers, PageLimit}
import org.lfdecentralizedtrust.splice.util.{Contract, ContractWithState, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.validator.store.ValidatorStore
import org.lfdecentralizedtrust.splice.wallet.store.WalletStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.AcsStoreId
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbValidatorStore(
    override val key: ValidatorStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    ingestionConfig: IngestionConfig,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbAppStore(
      storage = storage,
      acsTableName = ValidatorTables.acsTableName,
      interfaceViewsTableNameOpt = None,
      // Any change in the store descriptor will lead to previously deployed applications
      // forgetting all persisted data once they upgrade to the new version.
      acsStoreDescriptor = StoreDescriptor(
        version = 2,
        name = "DbValidatorStore",
        party = key.validatorParty,
        participant = participantId,
        key = Map(
          "validatorParty" -> key.validatorParty.toProtoPrimitive,
          "dsoParty" -> key.dsoParty.toProtoPrimitive,
        ),
      ),
      domainMigrationInfo = domainMigrationInfo,
      ingestionConfig,
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
                additionalWhere =
                  sql"""and contract_expires_at < ${now.plus(renewalDuration.asJava)}
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
