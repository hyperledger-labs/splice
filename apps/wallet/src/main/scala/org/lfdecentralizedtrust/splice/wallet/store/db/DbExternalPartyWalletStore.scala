// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.store.db

import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.db.DbMultiDomainAcsStore.StoreDescriptor
import org.lfdecentralizedtrust.splice.store.db.{AcsQueries, AcsTables, DbAppStore}
import org.lfdecentralizedtrust.splice.store.LimitHelpers
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import org.lfdecentralizedtrust.splice.wallet.store.{ExternalPartyWalletStore}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
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

  def storeId: Int = multiDomainAcsStore.storeId

  override def toString: String =
    show"DbExternalPartyWalletStore(externalParty=${key.externalParty})"

  override protected def acsContractFilter
      : org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractFilter[
        org.lfdecentralizedtrust.splice.wallet.store.db.WalletTables.ExternalPartyWalletAcsStoreRowData
      ] = ExternalPartyWalletStore.contractFilter(key)

}
