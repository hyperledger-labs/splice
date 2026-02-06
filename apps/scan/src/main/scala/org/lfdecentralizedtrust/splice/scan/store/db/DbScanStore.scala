// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  CloseContext,
  FlagCloseableAsync,
  SyncCloseable,
}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.{Member, ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
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
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.{
  ExternalPartyAmuletRules,
  TransferCommand,
  TransferCommandCounter,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicense
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient
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
  DbVotesAcsStoreQueryBuilder,
  DbVotesTxLogStoreQueryBuilder,
  Limit,
  PageLimit,
  SortOrder,
  TxLogStore,
  UpdateHistory,
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
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.AcsStoreId
import org.lfdecentralizedtrust.splice.store.db.TxLogQueries.TxLogStoreId
import slick.jdbc.canton.SQLActionBuilder

import java.time.Instant
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
  type CacheKey = java.lang.Long // caffeine metrics function demands AnyRefs
  type CacheValue = BigDecimal
}
class DbScanStore(
    override val key: ScanStore.Key,
    storage: DbStorage,
    isFirstSv: Boolean,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    createScanAggregatesReader: DbScanStore => ScanAggregatesReader,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    ingestionConfig: IngestionConfig,
    storeMetrics: DbScanStoreMetrics,
    initialRound: Long,
    acsStoreDescriptorUserVersion: Option[Long] = None,
    txLogStoreDescriptorUserVersion: Option[Long] = None,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbTxLogAppStore[TxLogEntry](
      storage,
      ScanTables.acsTableName,
      ScanTables.txLogTableName,
      interfaceViewsTableNameOpt = None,
      // Any change in the store descriptor will lead to previously deployed applications
      // forgetting all persisted data once they upgrade to the new version.
      acsStoreDescriptor = StoreDescriptor(
        version = 3,
        name = "DbScanStore",
        party = key.dsoParty,
        participant = participantId,
        key = Map(
          "dsoParty" -> key.dsoParty.toProtoPrimitive
        ),
        userVersion = acsStoreDescriptorUserVersion,
      ),
      txLogStoreDescriptor = StoreDescriptor(
        version = 1,
        name = "DbScanStore",
        party = key.dsoParty,
        participant = participantId,
        key = Map(
          "dsoParty" -> key.dsoParty.toProtoPrimitive
        ),
        userVersion = txLogStoreDescriptorUserVersion,
      ),
      domainMigrationInfo,
      ingestionConfig,
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

  import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture
  import multiDomainAcsStore.waitUntilAcsIngested

  override lazy val txLogConfig: org.lfdecentralizedtrust.splice.store.TxLogStore.Config[
    org.lfdecentralizedtrust.splice.scan.store.TxLogEntry
  ] = new DbScanTxLogStoreConfig(loggerFactory)

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    implicit def traceContext: TraceContext = TraceContext.empty
    Seq(
      AsyncCloseable(
        "db_scan_store",
        aggregator.map(_.close()),
        NonNegativeDuration.tryFromDuration(timeouts.shutdownNetwork.duration),
      ),
      SyncCloseable("db_scan_store_metrics", storeMetrics.close()),
      SyncCloseable("db_scan_acs_store", multiDomainAcsStore.close()),
    )
  }

  val aggregator: Future[ScanAggregator] =
    waitUntilAcsIngested().map(_ =>
      new ScanAggregator(
        storage,
        acsStoreId,
        txLogStoreId,
        isFirstSv,
        createScanAggregatesReader(this),
        loggerFactory,
        domainMigrationId,
        timeouts,
        initialRound.toInt,
      )
    )

  def aggregate()(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundTotals]] = {
    for {
      a <- aggregator
      lastAggregateRoundTotals <- a.aggregate()
      _ = lastAggregateRoundTotals.foreach(rt =>
        storeMetrics.latestAggregatedRound.updateValue(rt.closedRound)
      )
    } yield lastAggregateRoundTotals
  }

  def backFillAggregates()(implicit
      tc: TraceContext
  ): Future[Option[Long]] = {
    for {
      a <- aggregator
      backFilledRound <- a.backFillAggregates()
      _ = backFilledRound.foreach(r => storeMetrics.earliestAggregatedRound.updateValue(r))
    } yield backFilledRound
  }

  private[splice] def acsStoreId: AcsStoreId = multiDomainAcsStore.acsStoreId
  private[splice] def txLogStoreId: TxLogStoreId = multiDomainAcsStore.txLogStoreId
  // Round totals are derived from TxLog entries, and are therefore linked to that store
  private[splice] def roundTotalsStoreId: TxLogStoreId = txLogStoreId

  override def domainMigrationId: Long = domainMigrationInfo.currentMigrationId

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
      limit: Limit = Limit.DefaultLimit,
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

  override def listTransactions(
      pageEndEventId: Option[String],
      sortOrder: SortOrder,
      limit: PageLimit,
  )(implicit
      tc: TraceContext
  ): Future[Seq[TxLogEntry.TransactionTxLogEntry]] =
    waitUntilAcsIngested {
      val entryTypeCondition: SQLActionBuilder = inClause(
        "entry_type",
        List(
          EntryType.TransferTxLogEntry,
          EntryType.TapTxLogEntry,
          EntryType.MintTxLogEntry,
          EntryType.AbortTransferInstructionTxLogEntry,
        ),
      )
      // Literal sort order since Postgres complains when trying to bind it to a parameter
      val (compareEntryNumber, orderLimit) = sortOrder match {
        case SortOrder.Ascending =>
          (sql" > ", sql""" order by entry_number asc limit ${sqlLimit(limit)};""")
        case SortOrder.Descending =>
          (sql" < ", sql""" order by entry_number desc limit ${sqlLimit(limit)};""")
      }

      // TODO (#960): don't use the event id for pagination, use the entry number
      for {
        rows <- storage.query(
          pageEndEventId.fold(
            selectFromTxLogTable(
              txLogTableName,
              txLogStoreId,
              where = entryTypeCondition,
              orderLimit = orderLimit,
            )
          )(pageEndEventId =>
            selectFromTxLogTable(
              txLogTableName,
              txLogStoreId,
              where = (entryTypeCondition ++ sql" and entry_number " ++ compareEntryNumber ++
                sql"""(
                  select entry_number
                  from scan_txlog_store
                  where store_id = $txLogStoreId
                  and event_id = ${lengthLimited(pageEndEventId)}
                  and """ ++ entryTypeCondition ++ sql"""
              )""").toActionBuilder,
              orderLimit = orderLimit,
            )
          ),
          "listTransactions",
        )
        entries = rows.map(txLogEntryFromRow[TxLogEntry.TransactionTxLogEntry](txLogConfig))
      } yield entries

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

  override def lookupRoundOfLatestData()(implicit
      tc: TraceContext
  ): Future[Option[(Long, Instant)]] =
    waitUntilAcsIngested {
      for {
        row <- storage
          .querySingle(
            sql"""
            select   closed_round,
                     closed_round_effective_at
            from     round_totals
            where    store_id = $roundTotalsStoreId
            order by closed_round desc
            limit    1;
            """.as[(Long, Long)].headOption,
            "getRoundOfLatestData",
          )
          .value
        result <- row match {
          case Some((closedRound, effectiveAt)) =>
            Future.successful(
              Some((closedRound, CantonTimestamp.assertFromLong(micros = effectiveAt).toInstant))
            )
          case None =>
            Future.successful(None)
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

  override def getTotalRewardsCollectedEver()(implicit tc: TraceContext): Future[BigDecimal] =
    waitUntilAcsIngested {
      for {
        result <- storage.query(
          sql"""
          select coalesce(cumulative_app_rewards, 0) + coalesce(cumulative_validator_rewards, 0)
          from   round_totals
          where  store_id = $roundTotalsStoreId
          and    closed_round = (
                    select max(closed_round)
                    from round_totals
                    where store_id = $roundTotalsStoreId
                 );
          """.as[BigDecimal].headOption,
          "getTotalRewardsCollectedEver",
        )
      } yield result.getOrElse(0)
    }

  override def getRewardsCollectedInRound(round: Long)(implicit
      tc: TraceContext
  ): Future[BigDecimal] = waitUntilAcsIngested {
    for {
      result <- ensureAggregated(round) { _ =>
        storage.query(
          sql"""
            select coalesce(app_rewards, 0) + coalesce(validator_rewards, 0)
            from   round_totals
            where  store_id = $roundTotalsStoreId
            and    closed_round = $round;
            """.as[BigDecimal].headOption,
          "getRewardsCollectedInRound",
        )
      }
    } yield result.getOrElse(0)
  }

  override def getTopProvidersByAppRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] = waitUntilAcsIngested {
    for {
      rows <- ensureAggregated(asOfEndOfRound) { lastAggregatedRound =>
        if (lastAggregatedRound == asOfEndOfRound) {
          storage.query(
            sql"""
              select   rpt.party as provider,
                       rpt.cumulative_app_rewards as cumulative_app_rewards
              from     round_party_totals rpt
              join     active_parties ap
              on       rpt.store_id = ap.store_id
              and      rpt.party = ap.party
              and      rpt.closed_round = ap.closed_round
              and      rpt.store_id = $roundTotalsStoreId
              and      cumulative_app_rewards > 0
              order by cumulative_app_rewards desc, rpt.party desc
              limit $limit;
            """.as[(PartyId, BigDecimal)],
            "getTopProvidersByAppRewards",
          )
        } else {
          Future.successful(Seq())
        }
      }
    } yield rows
  }

  override def getTopValidatorsByValidatorRewards(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[(PartyId, BigDecimal)]] = waitUntilAcsIngested {
    for {
      rows <- ensureAggregated(asOfEndOfRound) { lastAggregatedRound =>
        if (lastAggregatedRound == asOfEndOfRound) {
          storage.query(
            sql"""
              select   rpt.party as validator,
                       rpt.cumulative_validator_rewards as cumulative_validator_rewards
              from     round_party_totals rpt
              join     active_parties ap
              on       rpt.store_id = ap.store_id
              and      rpt.party = ap.party
              and      rpt.closed_round = ap.closed_round
              and      rpt.store_id = $roundTotalsStoreId
              and      cumulative_validator_rewards > 0
              order by cumulative_validator_rewards desc, rpt.party desc
              limit $limit;
            """.as[(PartyId, BigDecimal)],
            "getTopValidatorsByValidatorRewards",
          )
        } else {
          Future.successful(Seq())
        }
      }
    } yield rows
  }

  override def getTopValidatorsByPurchasedTraffic(asOfEndOfRound: Long, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[HttpScanAppClient.ValidatorPurchasedTraffic]] = waitUntilAcsIngested {
    for {
      rows <- ensureAggregated(asOfEndOfRound) { lastAggregatedRound =>
        if (lastAggregatedRound == asOfEndOfRound) {
          storage.query(
            sql"""
              select   rpt.party as validator,
                       rpt.cumulative_traffic_num_purchases,
                       rpt.cumulative_traffic_purchased,
                       rpt.cumulative_traffic_purchased_cc_spent,
                       coalesce(
                         (
                           select   closed_round as last_purchased_in_round
                           from     round_party_totals
                           where    store_id = rpt.store_id
                           and      store_id = $roundTotalsStoreId
                           and      party = rpt.party
                           and      traffic_purchased > 0
                           order by closed_round desc
                           limit 1
                         ),
                         0
                       ) as last_purchased_in_round
              from     round_party_totals rpt
              join     active_parties ap
              on       rpt.store_id = ap.store_id
              and      rpt.party = ap.party
              and      rpt.closed_round = ap.closed_round
              and      rpt.store_id = $roundTotalsStoreId
              and      cumulative_traffic_purchased > 0
              order by cumulative_traffic_purchased desc, rpt.party desc
              limit $limit;
            """.as[(PartyId, Long, Long, BigDecimal, Long)],
            "getTopValidatorsByPurchasedTraffic",
          )
        } else {
          Future.successful(Seq())
        }
      }
    } yield rows.map((HttpScanAppClient.ValidatorPurchasedTraffic.apply _).tupled)
  }

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
    val validatorPartyIds = inClause("validator", validators)
    for {
      rows <- storage
        .query(
          selectFromAcsTable(
            ScanTables.acsTableName,
            acsStoreId,
            domainMigrationId,
            ValidatorLicense.COMPANION,
            where = validatorPartyIds,
          ),
          "getValidatorLicenseByValidator",
        )
    } yield {
      rows
        .map(
          contractFromRow(ValidatorLicense.COMPANION)(_)
        )
    }
  }

  override def getTotalPurchasedMemberTraffic(memberId: Member, synchronizerId: SynchronizerId)(
      implicit tc: TraceContext
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
                and member_traffic_member = ${lengthLimited(memberId.toProtoPrimitive)}
                and member_traffic_domain = ${lengthLimited(synchronizerId.toProtoPrimitive)}
             """.as[Long].headOption,
          "getTotalPurchasedMemberTraffic",
        )
        .value
    } yield sum.getOrElse(0L)
  }

  override def getAggregatedRounds()(implicit
      tc: TraceContext
  ): Future[Option[ScanAggregator.RoundRange]] =
    waitUntilAcsIngested {
      for {
        minMaxClosedRounds <- storage
          .querySingle(
            sql"""
            select min(closed_round) as min_round,
                   max(closed_round) as max_round
            from   round_totals
            where  store_id = $roundTotalsStoreId;
          """.as[(Option[Long], Option[Long])].headOption,
            "getAggregatedRounds",
          )
          .value
      } yield {
        minMaxClosedRounds.flatMap {
          _ match {
            case (Some(start), Some(end)) => Some(ScanAggregator.RoundRange(start, end))
            case _ => None
          }
        }
      }
    }

  override def getRoundTotals(startRound: Long, endRound: Long)(implicit
      tc: TraceContext
  ): Future[Seq[ScanAggregator.RoundTotals]] = {
    val q = sql"""
    select   #${ScanAggregator.roundTotalsColumns}
    from     round_totals
    where    store_id = $roundTotalsStoreId
    and      closed_round >= $startRound
    and      closed_round <= $endRound
    order by closed_round
    """
    waitUntilAcsIngested {
      for {
        roundTotals <- storage
          .query(
            q.as[ScanAggregator.RoundTotals],
            "getRoundTotals",
          )
      } yield roundTotals
    }
  }
  override def getRoundPartyTotals(startRound: Long, endRound: Long)(implicit
      tc: TraceContext
  ): Future[Seq[ScanAggregator.RoundPartyTotals]] = {
    val q = sql"""
    select   #${ScanAggregator.roundPartyTotalsColumns}
    from     round_party_totals
    where    store_id = $roundTotalsStoreId
    and      closed_round >= $startRound
    and      closed_round <= $endRound
    order by closed_round, party
    """
    waitUntilAcsIngested {
      for {
        roundPartyTotals <- storage
          .query(
            q.as[ScanAggregator.RoundPartyTotals],
            "getRoundPartyTotals",
          )
      } yield roundPartyTotals
    }
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
      actionName: Option[String],
      accepted: Option[Boolean],
      requester: Option[String],
      effectiveFrom: Option[String],
      effectiveTo: Option[String],
      limit: Limit,
  )(implicit tc: TraceContext): Future[Seq[DsoRules_CloseVoteRequestResult]] = {
    val query = listVoteRequestResultsQuery(
      txLogTableName = ScanTables.txLogTableName,
      txLogStoreId = txLogStoreId,
      dbType = EntryType.VoteRequestTxLogEntry,
      actionNameColumnName = "vote_action_name",
      acceptedColumnName = "vote_accepted",
      effectiveAtColumnName = "vote_effective_at",
      requesterNameColumnName = "vote_requester_name",
      actionName = actionName,
      accepted = accepted,
      requester = requester,
      effectiveFrom = effectiveFrom,
      effectiveTo = effectiveTo,
      limit = limit,
    )
    for {
      rows <- storage.query(query, "listVoteRequestResults")
      recentVoteResults = applyLimit("listVoteRequestResults", limit, rows)
        .map(
          txLogEntryFromRow[VoteRequestTxLogEntry](txLogConfig)
        )
        .map(_.result.getOrElse(throw txMissingField()))
    } yield recentVoteResults
  }

  override def listVoteRequestsByTrackingCid(
      trackingCids: Seq[VoteRequest.ContractId],
      limit: Limit,
  )(implicit tc: TraceContext): Future[Seq[Contract[VoteRequest.ContractId, VoteRequest]]] = {
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
