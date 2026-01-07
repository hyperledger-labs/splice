// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense as validatorCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractCompanion
import org.lfdecentralizedtrust.splice.store.db.AcsQueries.SelectFromAcsTableResult
import org.lfdecentralizedtrust.splice.store.db.StoreDescriptor
import org.lfdecentralizedtrust.splice.store.db.{
  AcsInterfaceViewRowData,
  AcsQueries,
  AcsTables,
  DbAppStore,
}
import org.lfdecentralizedtrust.splice.store.{Limit, LimitHelpers}
import org.lfdecentralizedtrust.splice.util.{Contract, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.wallet.store.ExternalPartyWalletStore
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.resource.DbStorage.Implicits.BuilderChain.*
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.topology.ParticipantId
import org.lfdecentralizedtrust.splice.config.IngestionConfig
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton
import slick.jdbc.canton.SQLActionBuilder

import scala.concurrent.*
import scala.jdk.OptionConverters.*

class DbExternalPartyWalletStore(
    override val key: ExternalPartyWalletStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    ingestionConfig: IngestionConfig,
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbAppStore(
      storage = storage,
      acsTableName = WalletTables.externalPartyAcsTableName,
      interfaceViewsTableNameOpt = None,
      acsStoreDescriptor = StoreDescriptor(
        version = 2,
        name = "DbExternalPartyWalletStore",
        party = key.externalParty,
        participant = participantId,
        key = Map(
          "externalParty" -> key.externalParty.toProtoPrimitive,
          "validatorParty" -> key.validatorParty.toProtoPrimitive,
          "dsoParty" -> key.dsoParty.toProtoPrimitive,
        ),
      ),
      domainMigrationInfo,
      ingestionConfig,
    )
    with ExternalPartyWalletStore
    with AcsTables
    with AcsQueries
    with LimitHelpers {

  import org.lfdecentralizedtrust.splice.store.db.AcsQueries.AcsStoreId
  import org.lfdecentralizedtrust.splice.util.FutureUnlessShutdownUtil.futureUnlessShutdownToFuture

  private def acsStoreId: AcsStoreId = multiDomainAcsStore.acsStoreId
  private def domainMigrationId: Long = domainMigrationInfo.currentMigrationId

  override def toString: String =
    show"DbExternalPartyWalletStore(externalParty=${key.externalParty})"

  override def acsContractFilter: org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractFilter[
    org.lfdecentralizedtrust.splice.wallet.store.db.WalletTables.ExternalPartyWalletAcsStoreRowData,
    AcsInterfaceViewRowData.NoInterfacesIngested,
  ] = ExternalPartyWalletStore.contractFilter(key)

  override def listSortedLivenessActivityRecords(
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

  private def listSortedRewardCoupons[C, TCid <: ContractId[?], T](
      companion: C,
      issuingRoundsMap: Map[Round, IssuingMiningRound],
      roundToIssuance: IssuingMiningRound => Option[BigDecimal],
      limit: Limit,
      ccValue: SQLActionBuilder = sql"rti.issuance",
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      tc: TraceContext,
  ): Future[Seq[(Contract[TCid, T], BigDecimal)]] = {
    val packageQualifiedName = companionClass.packageQualifiedName(companion)
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
                 from #${WalletTables.externalPartyAcsTableName} acs join round_to_issuance rti on acs.reward_coupon_round = rti.round
                 where acs.store_id = $acsStoreId
                   and migration_id = $domainMigrationId
                   and acs.package_name = ${packageQualifiedName.packageName}
                   and acs.template_id_qualified_name = ${packageQualifiedName.qualifiedName}
                 order by (acs.reward_coupon_round, -""" ++ ccValue ++ sql""")
                 limit ${sqlLimit(limit)}""").toActionBuilder
              .as[(SelectFromAcsTableResult, BigDecimal)],
            s"listSorted:$packageQualifiedName",
          )
        } yield applyLimit(s"listSorted:$packageQualifiedName", limit, result).map {
          case (row, issuance) =>
            val contract = contractFromRow(companion)(row)
            contract -> issuance
        }
    }
  }

}
