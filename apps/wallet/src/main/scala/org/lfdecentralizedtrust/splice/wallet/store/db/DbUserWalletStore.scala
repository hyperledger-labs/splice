// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.codegen.json.JsonLfReader
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.TransferPreapproval
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans as ansCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense as validatorCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions as subsCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.transferpreapproval.TransferPreapprovalProposal
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.{ContractCompanion, QueryResult}
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.{AcsStoreId, SelectFromAcsTableResult}
import org.lfdecentralizedtrust.splice.store.db.DbMultiDomainAcsStore.StoreDescriptor
import org.lfdecentralizedtrust.splice.store.db.{
  AcsQueries,
  AcsTables,
  DbTxLogAppStore,
  TxLogQueries,
}
import org.lfdecentralizedtrust.splice.store.{Limit, LimitHelpers, PageLimit, TxLogStore}
import org.lfdecentralizedtrust.splice.util.{Contract, QualifiedName, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.wallet.store
import org.lfdecentralizedtrust.splice.wallet.store.{
  BuyTrafficRequestTxLogEntry,
  TransferOfferTxLogEntry,
  TxLogEntry,
  UserWalletStore,
  UserWalletTxLogParser,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import DbStorage.Implicits.BuilderChain.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.toSQLActionBuilderChain
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import org.lfdecentralizedtrust.splice.store.db.TxLogQueries.TxLogStoreId
import slick.jdbc.canton.SQLActionBuilder

import scala.concurrent.*
import scala.jdk.OptionConverters.*

class DbUserWalletStore(
    override val key: UserWalletStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbTxLogAppStore[TxLogEntry](
      storage = storage,
      acsTableName = WalletTables.acsTableName,
      txLogTableName = WalletTables.txLogTableName,
      interfaceViewsTableNameOpt = Some(WalletTables.interfaceViewsTableName),
      // Any change in the store descriptor will lead to previously deployed applications
      // forgetting all persisted data once they upgrade to the new version.
      acsStoreDescriptor = StoreDescriptor(
        // Note that the V005__no_end_user_name_in_user_wallet_store.sql DB migration converts from version 1 descriptors
        // to version 2 descriptors.
        version = 2,
        name = "DbUserWalletStore",
        party = key.endUserParty,
        participant = participantId,
        key = Map(
          "endUserParty" -> key.endUserParty.toProtoPrimitive,
          "validatorParty" -> key.validatorParty.toProtoPrimitive,
          "dsoParty" -> key.dsoParty.toProtoPrimitive,
        ),
      ),
      txLogStoreDescriptor = StoreDescriptor(
        // Note that the V005__no_end_user_name_in_user_wallet_store.sql DB migration converts from version 1 descriptors
        // to version 2 descriptors.
        version = 2,
        name = "DbUserWalletStore",
        party = key.endUserParty,
        participant = participantId,
        key = Map(
          "endUserParty" -> key.endUserParty.toProtoPrimitive,
          "validatorParty" -> key.validatorParty.toProtoPrimitive,
          "dsoParty" -> key.dsoParty.toProtoPrimitive,
        ),
      ),
      domainMigrationInfo,
      participantId,
    )
    with UserWalletStore
    with AcsTables
    with AcsQueries
    with TxLogQueries[TxLogEntry]
    with LimitHelpers {

  import multiDomainAcsStore.waitUntilAcsIngested
  import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture

  private def acsStoreId: AcsStoreId = multiDomainAcsStore.acsStoreId
  private def txLogStoreId: TxLogStoreId = multiDomainAcsStore.txLogStoreId
  override def domainMigrationId: Long = domainMigrationInfo.currentMigrationId

  override def toString: String = show"DbUserWalletStore(endUserParty=${key.endUserParty})"

  override def acsContractFilter
      : org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractFilter[
        org.lfdecentralizedtrust.splice.wallet.store.db.WalletTables.UserWalletAcsStoreRowData,
        org.lfdecentralizedtrust.splice.wallet.store.db.WalletTables.UserWalletAcsInterfaceViewRowData,
      ] = UserWalletStore.contractFilter(key, domainMigrationId)

  override lazy val txLogConfig: org.lfdecentralizedtrust.splice.store.TxLogStore.Config[
    org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry
  ] {
    val parser: org.lfdecentralizedtrust.splice.wallet.store.UserWalletTxLogParser;
    def entryToRow
        : org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry => org.lfdecentralizedtrust.splice.wallet.store.db.WalletTables.UserWalletTxLogStoreRowData
  } = new TxLogStore.Config[TxLogEntry] {
    override val parser: org.lfdecentralizedtrust.splice.wallet.store.UserWalletTxLogParser =
      new UserWalletTxLogParser(loggerFactory, key.endUserParty)
    override def entryToRow
        : org.lfdecentralizedtrust.splice.wallet.store.TxLogEntry => org.lfdecentralizedtrust.splice.wallet.store.db.WalletTables.UserWalletTxLogStoreRowData =
      WalletTables.UserWalletTxLogStoreRowData.fromTxLogEntry
    override def encodeEntry = TxLogEntry.encode
    override def decodeEntry = TxLogEntry.decode
  }

  def listSortedValidatorFaucets(
      issuingRoundsMap: Map[Round, IssuingMiningRound],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    (
        Contract[
          validatorCodegen.ValidatorFaucetCoupon.ContractId,
          validatorCodegen.ValidatorFaucetCoupon,
        ],
        BigDecimal,
    )
  ]] = listSortedRewardCoupons(
    validatorCodegen.ValidatorFaucetCoupon.COMPANION,
    issuingRoundsMap,
    _.optIssuancePerValidatorFaucetCoupon.toScala.map(BigDecimal(_)),
    limit,
  )

  def listSortedLivenessActivityRecords(
      issuingRoundsMap: Map[Round, IssuingMiningRound],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    (
        Contract[
          validatorCodegen.ValidatorLivenessActivityRecord.ContractId,
          validatorCodegen.ValidatorLivenessActivityRecord,
        ],
        BigDecimal,
    )
  ]] = listSortedRewardCoupons(
    validatorCodegen.ValidatorLivenessActivityRecord.COMPANION,
    issuingRoundsMap,
    _.optIssuancePerValidatorFaucetCoupon.toScala.map(BigDecimal(_)),
    limit,
  )

  override def listSortedSvRewardCoupons(
      issuingRoundsMap: Map[Round, IssuingMiningRound],
      limit: Limit,
  )(implicit
      tc: TraceContext
  ): Future[
    Seq[
      (Contract[amuletCodegen.SvRewardCoupon.ContractId, amuletCodegen.SvRewardCoupon], BigDecimal)
    ]
  ] =
    listSortedRewardCoupons(
      amuletCodegen.SvRewardCoupon.COMPANION,
      issuingRoundsMap,
      r => Some(BigDecimal(r.issuancePerSvRewardCoupon)),
      limit,
      ccValue = sql"rti.issuance * acs.reward_coupon_weight",
    )

  private def listSortedRewardCoupons[C, TCid <: ContractId[_], T](
      companion: C,
      issuingRoundsMap: Map[Round, IssuingMiningRound],
      roundToIssuance: IssuingMiningRound => Option[BigDecimal],
      limit: Limit,
      ccValue: SQLActionBuilder = sql"rti.issuance",
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[(Contract[TCid, T], BigDecimal)]] = {
    val templateId = companionClass.typeId(companion)
    issuingRoundsMap
      .flatMap { case (round, contract) =>
        roundToIssuance(contract).map(round.number.longValue() -> _)
      }
      .map { case (round, issuance) =>
        sql"($round, $issuance)"
      }
      .reduceOption { (acc, next) =>
        (acc ++ sql"," ++ next).toActionBuilder
      } match {
      case None => Future.successful(Seq.empty) // no rounds = no results
      case Some(roundToIssuance) =>
        for {
          result <- storage.query(
            (sql"""
                 with round_to_issuance(round, issuance) as (values """ ++ roundToIssuance ++ sql""")
                 select
                   #${SelectFromAcsTableResult.sqlColumnsCommaSeparated()},""" ++ ccValue ++ sql"""
                 from #${WalletTables.acsTableName} acs join round_to_issuance rti on acs.reward_coupon_round = rti.round
                 where acs.store_id = $acsStoreId
                   and migration_id = $domainMigrationId
                   and acs.template_id_qualified_name = ${QualifiedName(templateId)}
                 order by (acs.reward_coupon_round, -""" ++ ccValue ++ sql""")
                 limit ${sqlLimit(limit)}""").toActionBuilder
              .as[(SelectFromAcsTableResult, BigDecimal)],
            s"listSorted${templateId.getEntityName}",
          )
        } yield applyLimit(s"listSorted${templateId.getEntityName}", limit, result).map {
          case (row, issuance) =>
            val contract = contractFromRow(companion)(row)
            contract -> issuance
        }
    }
  }

  override def listTransactions(
      beginAfterEventIdO: Option[String],
      limit: PageLimit,
  )(implicit
      lc: TraceContext
  ): Future[Seq[store.TxLogEntry.TransactionHistoryTxLogEntry]] = {
    // TODO (#960): don't use the event id for pagination, use the entry number
    waitUntilAcsIngested {
      for {
        rows <- storage
          .query(
            beginAfterEventIdO.fold(
              selectFromTxLogTable(
                WalletTables.txLogTableName,
                txLogStoreId,
                where = sql"tx_log_id = ${TxLogEntry.LogId.TransactionHistoryTxLog}",
                orderLimit = sql"order by entry_number desc limit ${sqlLimit(limit)}",
              )
            )(beginAfterEventId =>
              selectFromTxLogTable(
                WalletTables.txLogTableName,
                txLogStoreId,
                where = sql"""tx_log_id = ${TxLogEntry.LogId.TransactionHistoryTxLog}
                  and entry_number < (
                      select entry_number
                      from #${WalletTables.txLogTableName}
                      where store_id = $txLogStoreId
                      and tx_log_id = ${TxLogEntry.LogId.TransactionHistoryTxLog}
                      and event_id = ${lengthLimited(beginAfterEventId)}
                  )""",
                orderLimit = sql"order by entry_number desc limit ${sqlLimit(limit)}",
              )
            ),
            "listTransactions",
          )
        entries = rows.map(txLogEntryFromRow[TxLogEntry.TransactionHistoryTxLogEntry](txLogConfig))
      } yield entries
    }
  }

  override def listAnsEntries(now: CantonTimestamp, limit: Limit = Limit.DefaultLimit)(implicit
      tc: TraceContext
  ): Future[Seq[UserWalletStore.AnsEntryWithPayData]] = for {
    _ <- waitUntilAcsIngested()
    opName = "listAnsEntries"
    rows <- storage.query(
      // getting the payData from any subscription state is fine because it's
      // copied between states verbatim
      (sql"""select #${SelectFromAcsTableResult.sqlColumnsCommaSeparated("ansEntry.")},
                    st.create_arguments -> 'payData'
             from #${WalletTables.acsTableName} ansEntry,
                  #${WalletTables.acsTableName} ansEntryContext,
                  #${WalletTables.acsTableName} st,
                  #${WalletTables.acsTableName} sub
             where """ ++
        filterAcsStoreMigrationIds("ansEntry.", "ansEntryContext.", "sub.", "st.") ++
        sql" and " ++ subscriptionFilter(now) ++ sql"""
               and ansEntry.template_id_qualified_name =
                     ${QualifiedName(ansCodegen.AnsEntry.TEMPLATE_ID_WITH_PACKAGE_ID)}
               and ansEntryContext.template_id_qualified_name =
                     ${QualifiedName(ansCodegen.AnsEntryContext.TEMPLATE_ID_WITH_PACKAGE_ID)}
               and ansEntry.create_arguments ->> 'name' = ansEntryContext.create_arguments ->> 'name'
               and ansEntryContext.create_arguments ->> 'reference' = sub.create_arguments ->> 'reference'
             order by ansEntry.event_number
             limit ${sqlLimit(limit)}
        """).as[(SelectFromAcsTableResult, io.circe.Json)],
      opName,
    )
    parsed = rows.view.map { case (rawAnsEntry, payDataJson) =>
      val entry = contractFromRow(ansCodegen.AnsEntry.COMPANION)(rawAnsEntry)
      val subPayData: subsCodegen.SubscriptionPayData =
        subsCodegen.SubscriptionPayData.jsonDecoder().decode(new JsonLfReader(payDataJson.noSpaces))
      UserWalletStore.AnsEntryWithPayData(
        contractId = entry.contractId,
        expiresAt = entry.payload.expiresAt,
        entryName = entry.payload.name,
        amount = subPayData.paymentAmount.amount,
        unit = subPayData.paymentAmount.unit,
        paymentInterval = subPayData.paymentInterval,
        paymentDuration = subPayData.paymentDuration,
      )
    }
  } yield applyLimit(opName, limit, parsed).toSeq

  override def getLatestTransferOfferEventByTrackingId(trackingId: String)(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[TransferOfferTxLogEntry]]] =
    waitUntilAcsIngested {
      for {
        resultWithOffset <- storage
          .querySingle(
            selectFromTxLogTableWithOffset(
              WalletTables.txLogTableName,
              domainMigrationId,
              txLogStoreId,
              sql"entry_type = ${TxLogEntry.EntryType.TransferOfferTxLogEntry} and tracking_id = ${lengthLimited(trackingId)}",
              sql"order by entry_number desc limit 1",
            ).headOption,
            "getLatestTransferOfferEventByTrackingId",
          )
          .getOrElse(throw offsetExpectedError())
        entry = resultWithOffset.row.map(
          txLogEntryFromRow[TransferOfferTxLogEntry](txLogConfig)
        )
      } yield QueryResult[Option[TransferOfferTxLogEntry]](
        resultWithOffset.offset,
        entry,
      )
    }

  override def getLatestBuyTrafficRequestEventByTrackingId(trackingId: String)(implicit
      tc: TraceContext
  ): Future[QueryResult[Option[BuyTrafficRequestTxLogEntry]]] =
    waitUntilAcsIngested {
      for {
        resultWithOffset <- storage
          .querySingle(
            selectFromTxLogTableWithOffset(
              WalletTables.txLogTableName,
              domainMigrationId,
              txLogStoreId,
              sql"entry_type = ${TxLogEntry.EntryType.BuyTrafficRequestTxLogEntry} and tracking_id = ${lengthLimited(trackingId)}",
              sql"order by entry_number desc limit 1",
            ).headOption,
            "getLatestBuyTrafficRequestEventByTrackingId",
          )
          .getOrElse(throw offsetExpectedError())
        entry = resultWithOffset.row.map(
          txLogEntryFromRow[BuyTrafficRequestTxLogEntry](txLogConfig)
        )
      } yield QueryResult[Option[BuyTrafficRequestTxLogEntry]](
        resultWithOffset.offset,
        entry,
      )
    }

  override def listSubscriptions(now: CantonTimestamp, limit: Limit = Limit.DefaultLimit)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Seq[UserWalletStore.Subscription]] = waitUntilAcsIngested {
    import UserWalletStore.{Subscription, SubscriptionIdleState, SubscriptionPaymentState}
    val opName = "listSubscriptions"
    val idleStateFlag = 0
    for {
      rows <- storage.query(
        // The intent of the contract_expires_at filter is is to match the
        // expiry behavior of SvDsoStore#listExpiredAnsSubscriptions, not to
        // provide a grace period for subscription payments.
        (sql"""select #${SelectFromAcsTableResult.sqlColumnsCommaSeparated("st.")},
                      #${SelectFromAcsTableResult.sqlColumnsCommaSeparated("sub.")},
                      (case when st.template_id_qualified_name =
                                   ${QualifiedName(
            subsCodegen.SubscriptionIdleState.TEMPLATE_ID_WITH_PACKAGE_ID
          )}
                            then $idleStateFlag
                            else ${idleStateFlag + 1} end) which_state
              from #${WalletTables.acsTableName} st, #${WalletTables.acsTableName} sub
              where """ ++ filterAcsStoreMigrationIds("st.", "sub.") ++ sql"""
                    and """ ++ subscriptionFilter(now) ++ sql"""
              order by st.event_number
              limit ${sqlLimit(limit)}
          """).as[(SelectFromAcsTableResult, SelectFromAcsTableResult, Int)],
        opName,
      )
      joinedSubs = rows.view
        .map { case (rawState, rawSub, whichState) =>
          Subscription(
            contractFromRow(subsCodegen.Subscription.COMPANION)(rawSub),
            if (whichState == idleStateFlag)
              SubscriptionIdleState(
                contractFromRow(subsCodegen.SubscriptionIdleState.COMPANION)(rawState)
              )
            else
              SubscriptionPaymentState(
                contractFromRow(subsCodegen.SubscriptionPayment.COMPANION)(rawState)
              ),
          )
        }
    } yield applyLimit(opName, limit, joinedSubs).toSeq
  }

  private[this] def subscriptionFilter(now: CantonTimestamp) =
    sql"""((st.template_id_qualified_name =
               ${QualifiedName(subsCodegen.SubscriptionIdleState.TEMPLATE_ID_WITH_PACKAGE_ID)}
            and st.contract_expires_at >= $now)
           or st.template_id_qualified_name =
                ${QualifiedName(subsCodegen.SubscriptionPayment.TEMPLATE_ID_WITH_PACKAGE_ID)})
      and sub.template_id_qualified_name =
            ${QualifiedName(subsCodegen.Subscription.TEMPLATE_ID_WITH_PACKAGE_ID)}
      and (st.create_arguments ->> 'subscription') = sub.contract_id"""

  private[this] def filterAcsStoreMigrationIds(acsPrefixes: String*) =
    acsPrefixes
      .map(p => sql"#${p}store_id = $acsStoreId and #${p}migration_id = $domainMigrationId")
      .intercalate(sql" and ")

  def lookupTransferPreapproval(receiver: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[QueryResult[Option[Contract[TransferPreapproval.ContractId, TransferPreapproval]]]] =
    waitUntilAcsIngested {
      for {
        resultWithOffset <- storage
          .querySingle(
            selectFromAcsTableWithOffset(
              WalletTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              sql"""
            template_id_qualified_name = ${QualifiedName(
                  TransferPreapproval.TEMPLATE_ID_WITH_PACKAGE_ID
                )}
              and transfer_preapproval_receiver = ${receiver}
            """,
              sql"limit 1",
            ).headOption,
            "lookupTransferPreapproval",
          )
          .getOrElse(throw offsetExpectedError())
      } yield QueryResult(
        resultWithOffset.offset,
        resultWithOffset.row.map(
          contractFromRow(TransferPreapproval.COMPANION)(_)
        ),
      )
    }

  def lookupTransferPreapprovalProposal(
      receiver: PartyId
  )(implicit ec: ExecutionContext, tc: TraceContext): Future[QueryResult[
    Option[Contract[TransferPreapprovalProposal.ContractId, TransferPreapprovalProposal]]
  ]] =
    waitUntilAcsIngested {
      for {
        resultWithOffset <- storage
          .querySingle(
            selectFromAcsTableWithOffset(
              WalletTables.acsTableName,
              acsStoreId,
              domainMigrationId,
              sql"""
            template_id_qualified_name = ${QualifiedName(
                  TransferPreapprovalProposal.TEMPLATE_ID_WITH_PACKAGE_ID
                )}
              and transfer_preapproval_receiver = ${receiver}
            """,
              sql"limit 1",
            ).headOption,
            "lookupTransferPreapprovalProposal",
          )
          .getOrElse(throw offsetExpectedError())
      } yield QueryResult(
        resultWithOffset.offset,
        resultWithOffset.row.map(
          contractFromRow(TransferPreapprovalProposal.COMPANION)(_)
        ),
      )
    }
}
