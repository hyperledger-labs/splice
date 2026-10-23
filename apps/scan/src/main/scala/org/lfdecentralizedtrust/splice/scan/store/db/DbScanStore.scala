// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.canton.tracing.TraceContext

import org.lfdecentralizedtrust.splice.store.db.DbMultiDomainAcsStore

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.nonempty.NonEmpty
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{
  AsyncOrSyncCloseable,
  CloseContext,
  FlagCloseableAsync,
  SyncCloseable,
}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.{AnsEntry, AnsRules}
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.MemberTraffic
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvNodeState
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_UpdateSvRewardWeight
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.{
  ExternalPartyAmuletRules,
  TransferCommand,
  TransferCommandCounter,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicense
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.store.TxLogEntry.EntryType
import org.lfdecentralizedtrust.splice.scan.store.db.ScanTables.txLogTableName
import org.lfdecentralizedtrust.splice.scan.store.{
  OpenMiningRoundTxLogEntry,
  ScanStore,
  ScanTxLogParser,
  TransferCommandTxLogEntry,
  TxLogEntry,
  VoteRequestTxLogEntry,
}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractCompanion
import org.lfdecentralizedtrust.splice.store.db.StoreDescriptor
import org.lfdecentralizedtrust.splice.store.db.{
  AcsQueries,
  AcsTables,
  DbTxLogAppStore,
  TxLogQueries,
}
import org.lfdecentralizedtrust.splice.store.{
  AdditionalIngestionSink,
  DbVotesAcsStoreQueryBuilder,
  DbVotesTxLogStoreQueryBuilder,
  Limit,
  ResultsPage,
  TxLogStore,
  UpdateHistory,
  VoteResultsFilters,
}
import org.lfdecentralizedtrust.splice.util.{
  Contract,
  ContractWithState,
  PackageQualifiedName,
  QualifiedName,
  TemplateJsonDecoder,
}
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import io.grpc.Status
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import org.lfdecentralizedtrust.splice.store.UpdateHistoryQueries.UpdateHistoryQueries
import org.lfdecentralizedtrust.splice.store.db.AcsJdbcTypes.StoreMemberId
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.AcsStoreId
import org.lfdecentralizedtrust.splice.store.db.TxLogQueries.TxLogStoreId

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class DbScanTxLogStoreConfig(loggerFactory: NamedLoggerFactory)
    extends TxLogStore.Config[TxLogEntry] {
  override val parser: org.lfdecentralizedtrust.splice.scan.store.ScanTxLogParser =
    new ScanTxLogParser(
      loggerFactory
    )
  override def entryToRow: org.lfdecentralizedtrust.splice.scan.store.TxLogEntry => Option[
    org.lfdecentralizedtrust.splice.scan.store.db.ScanTables.ScanTxLogRowData
  ] =
    ScanTables.ScanTxLogRowData.fromTxLogEntry
  override def encodeEntry = TxLogEntry.encode
  override def decodeEntry = TxLogEntry.decode
}

object DbScanStore {

  /** Looks up the store ids, and creates the store. */
  def create(
      key: ScanStore.Key,
      storage: DbStorage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      domainMigrationId: Long,
      participantId: ParticipantId,
      ingestionConfig: IngestionConfig,
      storeMetrics: DbScanStoreMetrics,
      defaultLimit: Limit,
      acsStoreDescriptorUserVersion: Option[Long] = None,
      txLogStoreDescriptorUserVersion: Option[Long] = None,
      additionalIngestionSinks: Seq[AdditionalIngestionSink] = Seq.empty,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
      tc: TraceContext,
  ): Future[DbScanStore] =
    DbMultiDomainAcsStore
      .lookupStoreIds(
        storage,
        acsStoreDescriptor(key, participantId, acsStoreDescriptorUserVersion),
        Some(txLogStoreDescriptor(key, participantId, txLogStoreDescriptorUserVersion)),
      )
      .map { storeIds =>
        new DbScanStore(
          key = key,
          storage = storage,
          loggerFactory = loggerFactory,
          retryProvider = retryProvider,
          domainMigrationId = domainMigrationId,
          participantId = participantId,
          ingestionConfig = ingestionConfig,
          storeIds,
          storeMetrics = storeMetrics,
          defaultLimit = defaultLimit,
          acsStoreDescriptorUserVersion = acsStoreDescriptorUserVersion,
          txLogStoreDescriptorUserVersion = txLogStoreDescriptorUserVersion,
          additionalIngestionSinks = additionalIngestionSinks,
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
      key: ScanStore.Key,
      participantId: ParticipantId,
      acsStoreDescriptorUserVersion: Option[Long],
  ): StoreDescriptor =
    StoreDescriptor(
      version = 3,
      name = "DbScanStore",
      party = key.dsoParty,
      participant = participantId,
      key = Map(
        "dsoParty" -> key.dsoParty.toProtoPrimitive
      ),
      userVersion = acsStoreDescriptorUserVersion,
    )

  // WARNING: Do not change the txlog store descriptor.
  //
  // The TxLog store id identifies the ingestion sinks that store historical data and must witness
  // every single update: the TxLog and the votes store. Unlike the ACS, their content cannot be rebuilt
  // from the current state of the ledger. A new descriptor yields a new store id, and the history
  // ingested under the old one is lost.
  def txLogStoreDescriptor(
      key: ScanStore.Key,
      participantId: ParticipantId,
      txLogStoreDescriptorUserVersion: Option[Long],
  ): StoreDescriptor =
    StoreDescriptor(
      version = 1,
      name = "DbScanStore",
      party = key.dsoParty,
      participant = participantId,
      key = Map(
        "dsoParty" -> key.dsoParty.toProtoPrimitive
      ),
      userVersion = txLogStoreDescriptorUserVersion,
    )
}
class DbScanStore private (
    override val key: ScanStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    val domainMigrationId: Long,
    participantId: ParticipantId,
    ingestionConfig: IngestionConfig,
    storeIds: DbMultiDomainAcsStore.StoreIds,
    storeMetrics: DbScanStoreMetrics,
    override val defaultLimit: Limit,
    acsStoreDescriptorUserVersion: Option[Long],
    txLogStoreDescriptorUserVersion: Option[Long],
    additionalIngestionSinks: Seq[AdditionalIngestionSink],
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbTxLogAppStore[TxLogEntry](
      storage,
      ScanTables.acsTableName,
      ScanTables.txLogTableName,
      interfaceViewsTableNameOpt = None,
      acsStoreDescriptor =
        DbScanStore.acsStoreDescriptor(key, participantId, acsStoreDescriptorUserVersion),
      txLogStoreDescriptor =
        DbScanStore.txLogStoreDescriptor(key, participantId, txLogStoreDescriptorUserVersion),
      storeIds = storeIds,
      migrationId = domainMigrationId,
      ingestionConfig = ingestionConfig,
      additionalIngestionSinks = additionalIngestionSinks,
    )
    with ScanStore
    with AcsTables
    with AcsQueries
    with TxLogQueries[TxLogEntry]
    with UpdateHistoryQueries
    with FlagCloseableAsync
    with RetryProvider.Has
    with DbVotesAcsStoreQueryBuilder
    with DbVotesTxLogStoreQueryBuilder[TxLogEntry] {

  override protected implicit val dbProfile: DbStorage.Profile = storage.profile

  import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
  import multiDomainAcsStore.waitUntilAcsIngested

  override lazy val txLogConfig: org.lfdecentralizedtrust.splice.store.TxLogStore.Config[
    org.lfdecentralizedtrust.splice.scan.store.TxLogEntry
  ] = new DbScanTxLogStoreConfig(loggerFactory)

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    Seq(
      SyncCloseable("db_scan_store_metrics", storeMetrics.close()),
      SyncCloseable("db_scan_acs_store", multiDomainAcsStore.close()),
    )
  }

  private[splice] def acsStoreId: AcsStoreId = multiDomainAcsStore.acsStoreId
  override private[splice] def txLogStoreId: TxLogStoreId = multiDomainAcsStore.txLogStoreId
  override def lookupAmuletRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[AmuletRules.ContractId, AmuletRules]]] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            selectFromAcsTableWithState(
              ScanTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              AmuletRules.COMPANION,
              orderLimit = sql"""order by event_number desc limit 1""",
            ).headOption,
            "lookupAmuletRules",
          )
          .value
        contractWithState = row.map(
          contractWithStateFromRow(AmuletRules.COMPANION)(_)
        )
      } yield contractWithState
    }

  override def getExternalPartyAmuletRules()(implicit
      tc: TraceContext
  ): Future[ContractWithState[ExternalPartyAmuletRules.ContractId, ExternalPartyAmuletRules]] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            selectFromAcsTableWithState(
              ScanTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              ExternalPartyAmuletRules.COMPANION,
              orderLimit = sql"""order by event_number desc limit 1""",
            ).headOption,
            "lookupExternalPartyAmuletRules",
          )
          .value
        contractWithState = row.map(
          contractWithStateFromRow(ExternalPartyAmuletRules.COMPANION)(_)
        )
      } yield contractWithState.getOrElse(
        throw Status.NOT_FOUND
          .withDescription("No active ExternalPartyAmuletRules contract")
          .asRuntimeException
      )
    }

  override def lookupAnsRules()(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[AnsRules.ContractId, AnsRules]]] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            selectFromAcsTableWithState(
              ScanTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              AnsRules.COMPANION,
              orderLimit = sql"""order by event_number desc limit 1""",
            ).headOption,
            "lookupAnsRules",
          )
          .value
        contractWithState = row.map(
          contractWithStateFromRow(AnsRules.COMPANION)(_)
        )
      } yield contractWithState
    }

  override def listEntries(
      namePrefix: String,
      now: CantonTimestamp,
      limit: Limit = defaultLimit,
  )(implicit
      tc: TraceContext
  ): Future[
    Seq[ContractWithState[AnsEntry.ContractId, AnsEntry]]
  ] = waitUntilAcsIngested {
    val limitedPrefix = lengthLimited(namePrefix)
    for {
      rows <- storage
        .query(
          selectFromAcsTableWithState(
            ScanTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            AnsEntry.COMPANION,
            additionalWhere = sql"""
              and ans_entry_name ^@ $limitedPrefix
              and acs.contract_expires_at >= $now
            """,
            orderLimit = sql"""
                order by ans_entry_name
                limit ${sqlLimit(limit)}
            """,
          ),
          "listEntries",
        )
    } yield applyLimit("listEntries", limit, rows).map(
      contractWithStateFromRow(AnsEntry.COMPANION)(_)
    )
  }

  override def lookupEntryByParty(
      partyId: PartyId,
      now: CantonTimestamp,
  )(implicit tc: TraceContext): Future[
    Option[ContractWithState[AnsEntry.ContractId, AnsEntry]]
  ] = waitUntilAcsIngested {
    (for {
      row <- storage
        .querySingle(
          selectFromAcsTableWithState(
            ScanTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            AnsEntry.COMPANION,
            additionalWhere = sql"""
                and ans_entry_owner = $partyId
                and ans_entry_name >= ''
                and acs.contract_expires_at >= $now
            """,
            orderLimit = sql"""
                order by ans_entry_name
                limit 1
            """,
          ).headOption,
          "lookupEntryByParty",
        )
    } yield contractWithStateFromRow(AnsEntry.COMPANION)(row)).value
  }

  override def lookupEntryByName(name: String, now: CantonTimestamp)(implicit
      tc: TraceContext
  ): Future[
    Option[ContractWithState[AnsEntry.ContractId, AnsEntry]]
  ] = waitUntilAcsIngested {
    (for {
      row <- storage
        .querySingle(
          selectFromAcsTableWithState(
            ScanTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            AnsEntry.COMPANION,
            additionalWhere = sql"""
              and ans_entry_name = ${lengthLimited(name)}
              and acs.contract_expires_at >= $now
                 """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupEntryByName",
        )
    } yield contractWithStateFromRow(AnsEntry.COMPANION)(row)).value
  }

  override def lookupTransferPreapprovalByParty(
      partyId: PartyId
  )(implicit tc: TraceContext): Future[
    Option[ContractWithState[TransferPreapproval.ContractId, TransferPreapproval]]
  ] = waitUntilAcsIngested {
    (for {
      row <- storage
        .querySingle(
          selectFromAcsTableWithState(
            ScanTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            TransferPreapproval.COMPANION,
            additionalWhere = sql"""
                and transfer_preapproval_receiver = $partyId
            """,
            orderLimit = sql"""
                order by transfer_preapproval_valid_from desc limit 1
            """,
          ).headOption,
          "lookupTransferPreapprovalReceiver",
        )
    } yield contractWithStateFromRow(TransferPreapproval.COMPANION)(row)).value
  }

  override def lookupTransferCommandCounterByParty(
      partyId: PartyId
  )(implicit tc: TraceContext): Future[
    Option[ContractWithState[TransferCommandCounter.ContractId, TransferCommandCounter]]
  ] = waitUntilAcsIngested {
    (for {
      row <- storage
        .querySingle(
          selectFromAcsTableWithState(
            ScanTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            TransferCommandCounter.COMPANION,
            additionalWhere = sql"""
                and wallet_party = $partyId
            """,
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupTransferCommandCounterReceiver",
        )
    } yield contractWithStateFromRow(TransferCommandCounter.COMPANION)(row)).value
  }

  override def lookupFeaturedAppRight(
      providerPartyId: PartyId
  )(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[FeaturedAppRight.ContractId, FeaturedAppRight]]] =
    waitUntilAcsIngested {
      (for {
        row <- storage
          .querySingle(
            selectFromAcsTableWithState(
              ScanTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              FeaturedAppRight.COMPANION,
              additionalWhere = sql"""
                    and featured_app_right_provider = $providerPartyId
                 """,
              orderLimit = sql"limit 1",
            ).headOption,
            "findFeaturedAppRight",
          )
      } yield contractWithStateFromRow(FeaturedAppRight.COMPANION)(row)).value
    }

  override def listFeaturedAppRightsByProvider(
      providerPartyId: PartyId,
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[ContractWithState[FeaturedAppRight.ContractId, FeaturedAppRight]]] =
    waitUntilAcsIngested {
      for {
        rows <- storage.query(
          selectFromAcsTableWithState(
            ScanTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            FeaturedAppRight.COMPANION,
            additionalWhere = sql"""
                  and featured_app_right_provider = $providerPartyId
               """,
            orderLimit = sql"""order by event_number limit ${sqlLimit(limit)}""",
          ),
          "listFeaturedAppRightsByProvider",
        )
      } yield applyLimit("listFeaturedAppRightsByProvider", limit, rows).map(
        contractWithStateFromRow(FeaturedAppRight.COMPANION)
      )
    }

  override def getAmuletConfigForRound(round: Long)(implicit
      tc: TraceContext
  ): Future[OpenMiningRoundTxLogEntry] = waitUntilAcsIngested {
    for {
      row <- storage
        .querySingle(
          selectFromTxLogTable(
            txLogTableName,
            txLogStoreId,
            where = sql"""
                   entry_type = ${EntryType.OpenMiningRoundTxLogEntry} and
                   round = $round
              """,
            orderLimit = sql"order by entry_number desc limit 1",
          ).headOption,
          "getAmuletConfigForRound",
        )
        .value
      entry = row.map(txLogEntryFromRow[OpenMiningRoundTxLogEntry](txLogConfig))
      result <- entry match {
        case Some(omr: OpenMiningRoundTxLogEntry) =>
          Future.successful(omr)
        case None =>
          Future.failed(txLogNotFound())
      }
    } yield result
  }

  override def listSvNodeStates()(implicit tc: TraceContext): Future[Seq[SvNodeState]] =
    for {
      dsoRules <- getDsoRulesWithState()
      nodeStates <- Future.traverse(dsoRules.payload.svs.asScala.keys) { svPartyId =>
        getSvNodeState(PartyId.tryFromProtoPrimitive(svPartyId))
      }
    } yield nodeStates.map(_.contract.payload).toVector

  override def getTopValidatorLicenses(limit: Limit)(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ValidatorLicense.ContractId, ValidatorLicense]]] = waitUntilAcsIngested {
    for {
      rows <- storage
        .query(
          selectFromAcsTable(
            ScanTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            ValidatorLicense.COMPANION,
            orderLimit =
              sql"""order by validator_license_rounds_collected desc limit ${sqlLimit(limit)}""",
          ),
          "getTopValidatorLicenses",
        )
    } yield {
      applyLimit("getTopValidatorLicenses", limit, rows).map(
        contractFromRow(ValidatorLicense.COMPANION)(_)
      )
    }
  }

  override def getValidatorLicenseByValidator(validators: Vector[PartyId])(implicit
      tc: TraceContext
  ): Future[Seq[Contract[ValidatorLicense.ContractId, ValidatorLicense]]] = waitUntilAcsIngested {
    NonEmpty.from(validators.distinct) match {
      case None => Future.successful(Seq.empty)
      case Some(validators) =>
        val validatorPartyIds = DbStorage.toInClause("validator", validators)
        for {
          rows <- storage
            .query(
              selectFromAcsTable(
                ScanTables.acsTableName,
                acsStoreId,
                domainMigrationId,
                ValidatorLicense.COMPANION,
                where = validatorPartyIds,
                orderLimit = sql"limit ${sqlLimit(defaultLimit)}",
              ),
              "getValidatorLicenseByValidator",
            )
        } yield {
          applyLimit("getValidatorLicenseByValidator", defaultLimit, rows)
            .map(
              contractFromRow(ValidatorLicense.COMPANION)(_)
            )
        }
    }
  }

  override def getTotalPurchasedMemberTraffic(
      memberId: StoreMemberId,
      synchronizerId: SynchronizerId,
  )(implicit
      tc: TraceContext
  ): Future[Long] = waitUntilAcsIngested {
    for {
      sum <- storage
        .querySingle(
          sql"""
               select sum(total_traffic_purchased)
               from #${ScanTables.acsTableName}
               where store_id = $acsStoreId
                and migration_id = $domainMigrationId
                and package_name = ${MemberTraffic.PACKAGE_NAME}
                and template_id_qualified_name = ${QualifiedName(
              MemberTraffic.TEMPLATE_ID_WITH_PACKAGE_ID
            )}
                and member_traffic_member = $memberId
                and member_traffic_domain = ${lengthLimited(synchronizerId.toProtoPrimitive)}
             """.as[Long].headOption,
          "getTotalPurchasedMemberTraffic",
        )
        .value
    } yield sum.getOrElse(0L)
  }

  def lookupSvNodeState(svPartyId: PartyId)(implicit
      tc: TraceContext
  ): Future[Option[ContractWithState[SvNodeState.ContractId, SvNodeState]]] =
    lookupContractBySvParty(SvNodeState.COMPANION, svPartyId)

  private def lookupContractBySvParty[C, TCId <: ContractId[?], T](
      companion: C,
      svPartyId: PartyId,
  )(implicit
      companionClass: ContractCompanion[C, TCId, T],
      tc: TraceContext,
  ): Future[Option[ContractWithState[TCId, T]]] = {
    val templateId = companionClass.typeId(companion)
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            selectFromAcsTableWithState(
              ScanTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              companion,
              additionalWhere = sql"""and sv_party = $svPartyId""",
              orderLimit = sql"""limit 1""",
            ).headOption,
            s"lookupContractBySvParty[$templateId]",
          )
          .value
      } yield row.map(contractWithStateFromRow(companion)(_))
    }
  }

  override def listVoteRequestResults(
      filters: VoteResultsFilters,
      limit: Limit,
      after: Option[Long] = None,
  )(implicit tc: TraceContext): Future[ResultsPage[DsoRules_CloseVoteRequestResult]] = {
    val query = listVoteRequestResultsQuery(
      txLogTableName = ScanTables.txLogTableName,
      txLogStoreId = txLogStoreId,
      dbType = EntryType.VoteRequestTxLogEntry,
      actionNameColumnName = "vote_action_name",
      acceptedColumnName = "vote_accepted",
      requesterNameColumnName = "vote_requester_name",
      filters = filters,
      limit = limit,
      after = after,
    )
    for {
      rows <- storage.query(query, "listVoteRequestResults")
      limited = applyLimit("listVoteRequestResults", limit, rows)
      recentVoteResults = limited
        .map(
          txLogEntryFromRow[VoteRequestTxLogEntry](txLogConfig)
        )
        .map(_.result.getOrElse(throw txMissingField()))
      afterToken = limited.lastOption.map(_.entryNumber)
    } yield ResultsPage(recentVoteResults, afterToken)
  }

  override def countVoteRequestResults(
      filters: VoteResultsFilters
  )(implicit tc: TraceContext): Future[Long] = {
    val query = countVoteRequestResultsQuery(
      txLogTableName = ScanTables.txLogTableName,
      txLogStoreId = txLogStoreId,
      dbType = EntryType.VoteRequestTxLogEntry,
      actionNameColumnName = "vote_action_name",
      acceptedColumnName = "vote_accepted",
      requesterNameColumnName = "vote_requester_name",
      filters = filters,
    )
    storage
      .query(query, "countVoteRequestResults")
      .map(_.headOption.getOrElse(0L))
  }

  override def lookupLatestSvRewardWeightChange(
      svParty: PartyId,
      effectiveBefore: Option[String],
  )(implicit tc: TraceContext): Future[Option[Long]] = {
    val svPartyPath =
      "entry_data->'result'->'request'->'action'->'value'->'dsoAction'->'value'->>'svParty'"
    val where = (sql"""
           entry_type = ${EntryType.VoteRequestTxLogEntry} and
           vote_action_name = ${lengthLimited("SRARC_UpdateSvRewardWeight")} and
           vote_accepted = true and
           #$svPartyPath = ${svParty.toProtoPrimitive}""" ++ (effectiveBefore match {
      case Some(e) => sql" and vote_effective_at < ${lengthLimited(e)}"
      case None => sql""
    })).toActionBuilder
    for {
      row <- storage
        .querySingle(
          selectFromTxLogTable(
            txLogTableName,
            txLogStoreId,
            where = where,
            orderLimit = sql"order by vote_effective_at desc limit 1",
          ).headOption,
          "lookupLatestSvRewardWeightChange",
        )
        .value
    } yield row
      .map(txLogEntryFromRow[VoteRequestTxLogEntry](txLogConfig))
      .flatMap(_.result)
      .flatMap(newRewardWeightOf)
  }

  private def newRewardWeightOf(result: DsoRules_CloseVoteRequestResult): Option[Long] =
    result.request.action match {
      case arc: ARC_DsoRules =>
        arc.dsoAction match {
          case srarc: SRARC_UpdateSvRewardWeight =>
            Some(srarc.dsoRules_UpdateSvRewardWeightValue.newRewardWeight.longValue)
          case _ => None
        }
      case _ => None
    }

  override def listVoteRequestsByTrackingCid(
      trackingCids: Seq[VoteRequest.ContractId],
      limit: Limit,
  )(implicit tc: TraceContext): Future[Seq[Contract[VoteRequest.ContractId, VoteRequest]]] = {
    NonEmpty.from(trackingCids) match {
      case None => Future.successful(Seq.empty)
      case Some(trackingCids) =>
        for {
          result <- storage
            .query(
              listVoteRequestsByTrackingCidQuery(
                acsTableName = ScanTables.acsTableName,
                acsStoreId = acsStoreId,
                domainMigrationId = domainMigrationId,
                trackingCidColumnName = "vote_request_tracking_cid",
                trackingCids = trackingCids,
                limit = limit,
              ),
              "listVoteRequestsByTrackingCid",
            )
          records = applyLimit("listVoteRequestsByTrackingCid", limit, result)
        } yield records
          .map(contractFromRow(VoteRequest.COMPANION)(_))
    }
  }

  override def lookupVoteRequest(voteRequestCid: VoteRequest.ContractId)(implicit
      tc: TraceContext
  ): Future[Option[Contract[VoteRequest.ContractId, VoteRequest]]] = {
    for {
      result <- storage
        .querySingle(
          lookupVoteRequestQuery(
            ScanTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            "vote_request_tracking_cid",
            voteRequestCid,
          ),
          "lookupVoteRequest",
        )
        .value
    } yield result.map(contractFromRow(VoteRequest.COMPANION)(_))
  }

  override def lookupLatestTransferCommandEvents(sender: PartyId, nonce: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Map[TransferCommand.ContractId, TransferCommandTxLogEntry]] =
    waitUntilAcsIngested {
      for {
        // This query is linear in the number of events that match (sender, nonce).
        // Given that for each TransferCommand that's at most 2 and we expect few nonce conflicts
        // this is acceptable.
        result <- storage
          .query(
            sql"""
              with ranked_rows as (
                select #${TxLogQueries.SelectFromTxLogTableResult
                .sqlColumnsCommaSeparated()}, rank() over (partition by transfer_command_contract_id order by entry_number desc) from #${ScanTables.txLogTableName}
                where store_id = $txLogStoreId
                  and entry_type = ${TxLogEntry.EntryType.TransferCommandTxLogEntry}
                  and transfer_command_sender = ${sender}
                  and transfer_command_nonce = $nonce
              )
              select #${TxLogQueries.SelectFromTxLogTableResult.sqlColumnsCommaSeparated()}
              from ranked_rows
              where rank = 1
              limit $limit
            """.toActionBuilder.as[TxLogQueries.SelectFromTxLogTableResult],
            "getLatestTransferCommandEventByContractId",
          )
      } yield result
        .map(txLogEntryFromRow[TransferCommandTxLogEntry](txLogConfig))
        .map(entry => new TransferCommand.ContractId(entry.contractId) -> entry)
        .toMap
    }

  // TODO (#934): this method probably belongs in UpdateHistory instead
  override def lookupContractByRecordTime[C, TCId <: ContractId[?], T](
      companion: C,
      updateHistory: UpdateHistory,
      recordTime: CantonTimestamp,
  )(implicit
      companionClass: ContractCompanion[C, TCId, T],
      tc: TraceContext,
  ): Future[Option[Contract[TCId, T]]] = {
    val pqn @ PackageQualifiedName(packageName, QualifiedName(moduleName, entityName)) =
      companionClass.packageQualifiedName(companion)
    for {
      row <- storage
        .querySingle(
          selectFromUpdateCreatesTableResult(
            updateHistory.historyId,
            where = sql"""template_id_module_name = ${lengthLimited(moduleName)}
              and template_id_entity_name = ${lengthLimited(entityName)}
              and package_name = ${lengthLimited(packageName)}
              and record_time > $recordTime""",
            // TODO(#934): Order by row_id is suspicious
            orderLimit = sql"""order by row_id asc limit 1""",
          ).headOption,
          s"lookup[$pqn]",
        )
        .value
    } yield {
      row.map(contractFromEvent(companion)(_))
    }
  }
}
