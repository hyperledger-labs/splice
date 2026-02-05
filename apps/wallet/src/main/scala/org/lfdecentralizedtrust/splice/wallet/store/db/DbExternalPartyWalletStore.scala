// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.store.db

import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense as validatorCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.db.StoreDescriptor
import org.lfdecentralizedtrust.splice.store.db.{
  AcsInterfaceViewRowData,
  AcsQueries,
  AcsTables,
  DbAppStore,
  DbTransferInputQueries,
}
import org.lfdecentralizedtrust.splice.store.{Limit, LimitHelpers}
import org.lfdecentralizedtrust.splice.util.{Contract, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.wallet.store.ExternalPartyWalletStore
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.topology.ParticipantId
import org.lfdecentralizedtrust.splice.config.IngestionConfig

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
    override protected val ec: ExecutionContext,
    override protected val templateJsonDecoder: TemplateJsonDecoder,
    override protected val closeContext: CloseContext,
) extends DbAppStore(
      storage = storage,
      acsTableName = WalletTables.externalPartyAcsTableName,
      interfaceViewsTableNameOpt = None,
      acsStoreDescriptor = StoreDescriptor(
        version = 3,
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
    with DbTransferInputQueries
    with AcsTables
    with AcsQueries
    with LimitHelpers {

  import org.lfdecentralizedtrust.splice.store.db.AcsQueries.AcsStoreId

  override protected def acsStoreId: AcsStoreId = multiDomainAcsStore.acsStoreId
  override protected def domainMigrationId: Long = domainMigrationInfo.currentMigrationId
  override protected def acsTableName: String = WalletTables.externalPartyAcsTableName
  override protected def dbStorage: DbStorage = storage

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

}
