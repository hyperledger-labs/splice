// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.wallet.store.db

import com.daml.network.codegen.java.splice.amulet as amuletCodegen
import com.daml.network.environment.RetryProvider
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.store.db.DbMultiDomainAcsStore.StoreDescriptor
import com.daml.network.store.db.{AcsQueries, AcsTables, DbAppStore}
import com.daml.network.store.{Limit, LimitHelpers}
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import com.daml.network.wallet.store.{ExternalPartyWalletStore}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.topology.ParticipantId

import scala.concurrent.*

class DbExternalPartyWalletStore(
    override val key: ExternalPartyWalletStore.Key,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
)(implicit
    ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbAppStore(
      storage = storage,
      acsTableName = WalletTables.externalPartyAcsTableName,
      storeDescriptor = StoreDescriptor(
        version = 1,
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
      participantId,
      enableissue12777Workaround = false,
    )
    with ExternalPartyWalletStore
    with AcsTables
    with AcsQueries
    with LimitHelpers {

  import multiDomainAcsStore.waitUntilAcsIngested

  def storeId: Int = multiDomainAcsStore.storeId

  override def toString: String =
    show"DbExternalPartyWalletStore(externalParty=${key.externalParty})"

  override protected def acsContractFilter = ExternalPartyWalletStore.contractFilter(key)

  /** Returns the validator reward coupon sorted by their round in ascending order. Optionally limited by `maxNumInputs`
    * and optionally filtered by a set of issuing rounds.
    */
  override def listSortedValidatorRewards(
      activeIssuingRoundsO: Option[Set[Long]],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    Contract[amuletCodegen.ValidatorRewardCoupon.ContractId, amuletCodegen.ValidatorRewardCoupon]
  ]] = for {
    _ <- waitUntilAcsIngested()
    rewards <- multiDomainAcsStore.listContracts(
      amuletCodegen.ValidatorRewardCoupon.COMPANION
    )
  } yield applyLimit(
    "listSortedValidatorRewards",
    limit,
    // TODO(#6119) Perform filter, sort, and limit in the database query
    rewards.view
      .filter(rw =>
        activeIssuingRoundsO match {
          case Some(rounds) => rounds.contains(rw.payload.round.number)
          case None => true
        }
      )
      .map(_.contract)
      .toSeq
      .sortBy(_.payload.round.number),
  )
}
