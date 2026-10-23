// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.store.db

import scala.concurrent.Future

import org.lfdecentralizedtrust.splice.store.db.DbMultiDomainAcsStore

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense as validatorCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.store.db.StoreDescriptor
import org.lfdecentralizedtrust.splice.store.db.{
  AcsInterfaceViewRowData,
  AcsQueries,
  AcsTables,
  DbAppStore,
  DbTransferInputQueries,
}
import org.lfdecentralizedtrust.splice.store.{Limit, LimitHelpers}
import org.lfdecentralizedtrust.splice.util.{Contract, ContractWithState, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.wallet.store.ExternalPartyWalletStore
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.topology.ParticipantId
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.SvRewardCoupon
import org.lfdecentralizedtrust.splice.config.IngestionConfig

import scala.concurrent.*
import scala.jdk.OptionConverters.*

object DbExternalPartyWalletStore {

  /** Looks up the store ids, and creates the store. */
  def create(
      key: ExternalPartyWalletStore.Key,
      storage: DbStorage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      domainMigrationId: Long,
      participantId: ParticipantId,
      ingestionConfig: IngestionConfig,
      defaultLimit: Limit,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
      tc: TraceContext,
  ): Future[DbExternalPartyWalletStore] =
    DbMultiDomainAcsStore
      .lookupStoreIds(storage, acsStoreDescriptor(key, participantId), None)
      .map { storeIds =>
        new DbExternalPartyWalletStore(
          key = key,
          storage = storage,
          loggerFactory = loggerFactory,
          retryProvider = retryProvider,
          domainMigrationId = domainMigrationId,
          participantId = participantId,
          ingestionConfig = ingestionConfig,
          storeIds,
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
      key: ExternalPartyWalletStore.Key,
      participantId: ParticipantId,
  ): StoreDescriptor =
    StoreDescriptor(
      version = 3,
      name = "DbExternalPartyWalletStore",
      party = key.externalParty,
      participant = participantId,
      key = Map(
        "externalParty" -> key.externalParty.toProtoPrimitive,
        "validatorParty" -> key.validatorParty.toProtoPrimitive,
        "dsoParty" -> key.dsoParty.toProtoPrimitive,
      ),
    )
}

class DbExternalPartyWalletStore private (
    override val key: ExternalPartyWalletStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    val domainMigrationId: Long,
    participantId: ParticipantId,
    ingestionConfig: IngestionConfig,
    storeIds: DbMultiDomainAcsStore.StoreIds,
    override val defaultLimit: Limit,
)(implicit
    override protected val ec: ExecutionContext,
    override protected val templateJsonDecoder: TemplateJsonDecoder,
    override protected val closeContext: CloseContext,
) extends DbAppStore(
      storage = storage,
      acsTableName = WalletTables.externalPartyAcsTableName,
      interfaceViewsTableNameOpt = None,
      acsStoreDescriptor = DbExternalPartyWalletStore.acsStoreDescriptor(key, participantId),
      storeIds = storeIds,
      migrationId = domainMigrationId,
      ingestionConfig = ingestionConfig,
    )
    with ExternalPartyWalletStore
    with DbTransferInputQueries
    with AcsTables
    with AcsQueries
    with LimitHelpers {

  import org.lfdecentralizedtrust.splice.store.db.AcsQueries.AcsStoreId
  import multiDomainAcsStore.waitUntilAcsIngested

  override protected def acsStoreId: AcsStoreId = multiDomainAcsStore.acsStoreId
  override protected def acsTableName: String = WalletTables.externalPartyAcsTableName
  override protected def dbStorage: DbStorage = storage

  override def toString: String =
    show"DbExternalPartyWalletStore(externalParty=${key.externalParty})"

  override def acsContractFilter: org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractFilter[
    org.lfdecentralizedtrust.splice.wallet.store.db.WalletTables.ExternalPartyWalletAcsStoreRowData,
    AcsInterfaceViewRowData.NoInterfacesIngested,
  ] = ExternalPartyWalletStore.contractFilter(key)

  override def listRewardCouponsV2(
      includeUnassigned: Boolean,
      includeAssigned: Boolean,
      limit: Limit = defaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    ContractWithState[amuletCodegen.RewardCouponV2.ContractId, amuletCodegen.RewardCouponV2]
  ]] =
    waitUntilAcsIngested {
      queryRewardCouponsV2(includeUnassigned, includeAssigned, limit)
    }

  override def listSortedLivenessActivityRecords(
      issuingRoundsMap: Map[Round, IssuingMiningRound],
      limit: Limit = defaultLimit,
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
      limit: Limit = defaultLimit,
  )(implicit tc: TraceContext): Future[
    Seq[(Contract[SvRewardCoupon.ContractId, SvRewardCoupon], BigDecimal)]
  ] =
    for {
      coupons <- multiDomainAcsStore.listContracts(amuletCodegen.SvRewardCoupon.COMPANION)
    } yield applyLimit(
      "listSortedRewardCoupons",
      limit,
      coupons
        .flatMap { c =>
          issuingRoundsMap.get(c.payload.round).map { i =>
            val quantity =
              BigDecimal(c.payload.weight.longValue) * BigDecimal(i.issuancePerSvRewardCoupon)
            (c.contract, quantity)
          }
        }
        .sorted(
          Ordering[(Long, BigDecimal)].on(
            (x: (Contract[SvRewardCoupon.ContractId, SvRewardCoupon], BigDecimal)) =>
              (x._1.payload.round.number, -x._2)
          )
        ),
    )
}
