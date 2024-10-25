// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.store

import org.lfdecentralizedtrust.splice.codegen.java.splice.{amulet as amuletCodegen}
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.*
import org.lfdecentralizedtrust.splice.store.{AppStore, Limit}
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.wallet.store.db.DbExternalPartyWalletStore
import org.lfdecentralizedtrust.splice.wallet.store.db.WalletTables.ExternalPartyWalletAcsStoreRowData
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.{DbStorage, Storage}
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

/** A store for serving all queries for an external party. */
trait ExternalPartyWalletStore extends AppStore with NamedLogging {

  /** The key identifying the parties considered by this store. */
  def key: ExternalPartyWalletStore.Key

  /** Returns the validator reward coupon sorted by their round in ascending order. Optionally limited by `maxNumInputs`
    * and optionally filtered by a set of issuing rounds.
    */
  def listSortedValidatorRewards(
      activeIssuingRoundsO: Option[Set[Long]],
      limit: Limit = Limit.DefaultLimit,
  )(implicit tc: TraceContext): Future[Seq[
    Contract[amuletCodegen.ValidatorRewardCoupon.ContractId, amuletCodegen.ValidatorRewardCoupon]
  ]]
}

object ExternalPartyWalletStore {
  def apply(
      key: Key,
      storage: Storage,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      domainMigrationInfo: DomainMigrationInfo,
      participantId: ParticipantId,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      close: CloseContext,
  ): ExternalPartyWalletStore = {
    storage match {
      case dbStorage: DbStorage =>
        new DbExternalPartyWalletStore(
          key,
          dbStorage,
          loggerFactory,
          retryProvider,
          domainMigrationInfo,
          participantId,
        )
      case storageType => throw new RuntimeException(s"Unsupported storage type $storageType")
    }
  }

  case class Key(
      /** The party-id of the DSO issuing CC managed by this external party's wallet. */
      dsoParty: PartyId,

      /** The party-id of the wallet's validator */
      validatorParty: PartyId,

      /** The party-id of the external party */
      externalParty: PartyId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("externalParty", _.externalParty),
      param("validatorParty", _.validatorParty),
      param("dsoParty", _.dsoParty),
    )
  }

  /** Contract of a wallet store for a specific external party. */
  def contractFilter(
      key: Key
  ): ContractFilter[ExternalPartyWalletAcsStoreRowData] = {
    val endUser = key.externalParty.toProtoPrimitive
    val dso = key.dsoParty.toProtoPrimitive

    SimpleContractFilter(
      key.externalParty,
      Map(
        mkFilter(amuletCodegen.ValidatorRewardCoupon.COMPANION) { co =>
          co.payload.dso == dso &&
          co.payload.user == endUser
        }(co =>
          ExternalPartyWalletAcsStoreRowData(co, rewardCouponRound = Some(co.payload.round.number))
        )
      ),
    )
  }
}
