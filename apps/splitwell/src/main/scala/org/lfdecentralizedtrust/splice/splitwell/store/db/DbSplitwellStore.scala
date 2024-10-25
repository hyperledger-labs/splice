// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.splitwell.store.db

import com.daml.ledger.javaapi.data.codegen.ContractId
import org.lfdecentralizedtrust.splice.automation.TransferFollowTrigger
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell as splitwellCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as walletCodegen
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.splitwell.config.SplitwellSynchronizerConfig
import org.lfdecentralizedtrust.splice.splitwell.store.SplitwellStore
import org.lfdecentralizedtrust.splice.store.db.DbMultiDomainAcsStore.StoreDescriptor
import org.lfdecentralizedtrust.splice.store.{LimitHelpers, MultiDomainAcsStore}
import org.lfdecentralizedtrust.splice.store.db.{AcsQueries, AcsTables, DbAppStore}
import org.lfdecentralizedtrust.splice.util.{
  AssignedContract,
  Contract,
  ContractWithState,
  QualifiedName,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import scala.concurrent.{ExecutionContext, Future}

class DbSplitwellStore(
    override val key: SplitwellStore.Key,
    override protected[this] val domainConfig: SplitwellSynchronizerConfig,
    storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
)(implicit
    override protected val ec: ExecutionContext,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends DbAppStore(
      storage = storage,
      acsTableName = SplitwellTables.acsTableName,
      // Any change in the store descriptor will lead to previously deployed applications
      // forgetting all persisted data once they upgrade to the new version.
      storeDescriptor = StoreDescriptor(
        version = 1,
        name = "DbSplitwellStore",
        party = key.providerParty,
        participant = participantId,
        key = Map(
          "providerParty" -> key.providerParty.toProtoPrimitive
        ),
      ),
      domainMigrationInfo = domainMigrationInfo,
      participantId = participantId,
      enableissue12777Workaround = false,
    )
    with AcsTables
    with AcsQueries
    with LimitHelpers
    with SplitwellStore {

  import MultiDomainAcsStore.*
  override lazy val acsContractFilter = SplitwellStore.contractFilter(key)

  import multiDomainAcsStore.waitUntilAcsIngested

  private def storeId: Int = multiDomainAcsStore.storeId
  def domainMigrationId: Long = domainMigrationInfo.currentMigrationId

  override def lookupInstallWithOffset(
      domainId: DomainId,
      user: PartyId,
  )(implicit tc: TraceContext): Future[QueryResult[Option[
    Contract[splitwellCodegen.SplitwellInstall.ContractId, splitwellCodegen.SplitwellInstall]
  ]]] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            SplitwellTables.acsTableName,
            storeId,
            domainMigrationId,
            where = sql"""template_id_qualified_name = ${QualifiedName(
                splitwellCodegen.SplitwellInstall.TEMPLATE_ID_WITH_PACKAGE_ID
              )} and assigned_domain = $domainId
              and install_user = ${user}""",
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupInstallWithOffset",
        )
        .getOrElse(throw offsetExpectedError())
      assigned = resultWithOffset.row.map(r =>
        contractFromRow(splitwellCodegen.SplitwellInstall.COMPANION)(r.acsRow)
      )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      assigned,
    )
  }

  override def lookupGroupWithOffset(
      owner: PartyId,
      id: splitwellCodegen.GroupId,
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[ContractWithState[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]]
    ]
  ] = waitUntilAcsIngested {
    for {
      resultWithOffset <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            SplitwellTables.acsTableName,
            storeId,
            domainMigrationId,
            where = sql"""template_id_qualified_name = ${QualifiedName(
                splitwellCodegen.Group.TEMPLATE_ID_WITH_PACKAGE_ID
              )} and group_owner = ${owner} and group_id = ${lengthLimited(id.unpack)}""",
            orderLimit = sql"limit 1",
          ).headOption,
          "lookupInstallWithOffset",
        )
        .getOrElse(throw offsetExpectedError())
      assigned = resultWithOffset.row.map(
        contractWithStateFromRow(splitwellCodegen.Group.COMPANION)(_)
      )
    } yield MultiDomainAcsStore.QueryResult(
      resultWithOffset.offset,
      assigned,
    )
  }

  override def listGroups(
      user: PartyId
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[ContractWithState[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]]] =
    waitUntilAcsIngested {
      for {
        rows <- storage
          .query(
            selectFromAcsTableWithState(
              SplitwellTables.acsTableName,
              storeId,
              domainMigrationId,
              where = sql"""
              template_id_qualified_name = ${QualifiedName(
                  splitwellCodegen.Group.TEMPLATE_ID_WITH_PACKAGE_ID
                )}
              """,
            ),
            "listGroups",
          )
        result = rows.map(
          contractWithStateFromRow(
            splitwellCodegen.Group.COMPANION
          )(_)
        )
        // TODO(#9249): filter on the database side
        filteredResult = result.filter(c => groupMembers(c.payload).contains(user.toProtoPrimitive))
      } yield filteredResult
    }

  override def listGroupInvites(owner: PartyId)(implicit traceContext: TraceContext): Future[
    Seq[ContractWithState[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite]]
  ] = waitUntilAcsIngested {
    for {
      rows <- storage
        .query(
          selectFromAcsTableWithState(
            SplitwellTables.acsTableName,
            storeId,
            domainMigrationId,
            where = sql"""
              template_id_qualified_name = ${QualifiedName(
                splitwellCodegen.GroupInvite.TEMPLATE_ID_WITH_PACKAGE_ID
              )} and group_owner = ${owner}
              """,
          ),
          "listGroupInvites",
        )
      result = rows.map(
        contractWithStateFromRow(
          splitwellCodegen.GroupInvite.COMPANION
        )(_)
      )
    } yield result
  }

  override def listAcceptedGroupInvites(owner: PartyId, groupId: String)(implicit
      traceContext: TraceContext
  ): Future[Seq[ContractWithState[
    splitwellCodegen.AcceptedGroupInvite.ContractId,
    splitwellCodegen.AcceptedGroupInvite,
  ]]] = waitUntilAcsIngested {
    for {
      rows <- storage
        .query(
          selectFromAcsTableWithState(
            SplitwellTables.acsTableName,
            storeId,
            domainMigrationId,
            where = sql"""
              template_id_qualified_name = ${QualifiedName(
                splitwellCodegen.AcceptedGroupInvite.TEMPLATE_ID_WITH_PACKAGE_ID
              )} and group_owner = ${owner}
              and group_id = ${lengthLimited(groupKey(owner, groupId).id.unpack)}
              """,
          ),
          "listAcceptedGroupInvites",
        )
      result = rows.map(
        contractWithStateFromRow(
          splitwellCodegen.AcceptedGroupInvite.COMPANION
        )(_)
      )
    } yield result
  }

  override def listBalanceUpdates(user: PartyId, key: splitwellCodegen.GroupKey)(implicit
      traceContext: TraceContext
  ): Future[Seq[
    ContractWithState[splitwellCodegen.BalanceUpdate.ContractId, splitwellCodegen.BalanceUpdate]
  ]] = waitUntilAcsIngested {
    for {
      rows <- storage
        .query(
          selectFromAcsTableWithState(
            SplitwellTables.acsTableName,
            storeId,
            domainMigrationId,
            where = sql"""
              template_id_qualified_name = ${QualifiedName(
                splitwellCodegen.BalanceUpdate.TEMPLATE_ID_WITH_PACKAGE_ID
              )} and group_id = ${lengthLimited(key.id.unpack)}
              """,
            orderLimit = sql"""order by event_number desc""",
          ),
          "listBalanceUpdates",
        )
      result = rows.map(
        contractWithStateFromRow(
          splitwellCodegen.BalanceUpdate.COMPANION
        )(_)
      )
      // TODO(#9249): filter on the database side
      filteredResult = result.filter(c =>
        groupMembers(c.payload.group).contains(user.toProtoPrimitive)
      )
    } yield filteredResult
  }

  override def listTransferrableGroups()(implicit
      tc: TraceContext
  ): Future[Map[DomainId, Seq[splitwellCodegen.Group.ContractId]]] = for {
    // find all groups still on 'others' domains
    othersGroups <- Future
      .traverse(domainConfig.splitwell.others) { otherDomain =>
        for {
          otherDomainId <- domains.waitForDomainConnection(otherDomain.alias)
          groups <- multiDomainAcsStore.listContractsOnDomain(
            splitwellCodegen.Group.COMPANION,
            otherDomainId,
          )
        } yield otherDomainId -> groups
      }
      .map(_.view.filter(_._2.nonEmpty).toMap)
    allGroupMembers = othersGroups.view
      .flatMap(_._2.view.flatMap(co => groupMembers(co.payload)))
      .toSet
    preferredId <- domains.waitForDomainConnection(domainConfig.splitwell.preferred.alias)
    // find members of 'othersGroups' with install contracts on 'preferred'
    preferredInstalledMembers <- multiDomainAcsStore
      .listContractsOnDomain(
        splitwellCodegen.SplitwellInstall.COMPANION,
        preferredId,
      )
      // TODO(#9249): filter on the database side
      .map(_.filter(co => allGroupMembers(co.payload.user)))
      .map(_.view.map(_.payload.user).toSet)
  } yield othersGroups.collect(Function unlift { case (otherId, groups) =>
    val transferrable = groups.collect {
      // only respond with groups where every member is installed on 'preferred'
      case co if groupMembers(co.payload) subsetOf preferredInstalledMembers => co.contractId
    }
    Option.when(transferrable.nonEmpty)(otherId -> transferrable)
  })

  override def listSplitwellInstalls(
      user: PartyId
  )(implicit traceContext: TraceContext): Future[Seq[
    AssignedContract[
      splitwellCodegen.SplitwellInstall.ContractId,
      splitwellCodegen.SplitwellInstall,
    ]
  ]] = waitUntilAcsIngested {
    for {
      rows <- storage
        .query(
          selectFromAcsTableWithState(
            SplitwellTables.acsTableName,
            storeId,
            domainMigrationId,
            where = sql"""
              template_id_qualified_name = ${QualifiedName(
                splitwellCodegen.SplitwellInstall.TEMPLATE_ID_WITH_PACKAGE_ID
              )} and install_user = ${user}
              and assigned_domain is not null
              """,
          ),
          "listSplitwellInstalls",
        )
      result = rows.map(
        assignedContractFromRow(
          splitwellCodegen.SplitwellInstall.COMPANION
        )(_)
      )
    } yield result
  }

  override def listSplitwellRules()(implicit traceContext: TraceContext): Future[Seq[
    AssignedContract[
      splitwellCodegen.SplitwellRules.ContractId,
      splitwellCodegen.SplitwellRules,
    ]
  ]] = waitUntilAcsIngested {
    for {
      rows <- storage
        .query(
          selectFromAcsTableWithState(
            SplitwellTables.acsTableName,
            storeId,
            domainMigrationId,
            where = sql"""
              template_id_qualified_name = ${QualifiedName(
                splitwellCodegen.SplitwellRules.TEMPLATE_ID_WITH_PACKAGE_ID
              )} and assigned_domain is not null
              """,
          ),
          "listSplitwellRules",
        )
      result = rows.map(
        assignedContractFromRow(
          splitwellCodegen.SplitwellRules.COMPANION
        )(_)
      )
    } yield result
  }

  override def lookupSplitwellRules(
      domainId: DomainId
  )(implicit tc: TraceContext): Future[QueryResult[Option[
    Contract[
      splitwellCodegen.SplitwellRules.ContractId,
      splitwellCodegen.SplitwellRules,
    ]
  ]]] = waitUntilAcsIngested {
    for {
      row <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            SplitwellTables.acsTableName,
            storeId,
            domainMigrationId,
            where = sql"""
              template_id_qualified_name = ${QualifiedName(
                splitwellCodegen.SplitwellRules.TEMPLATE_ID_WITH_PACKAGE_ID
              )} and assigned_domain = $domainId
              """,
          ).headOption,
          "lookupSplitwellRules",
        )
        .getOrRaise(offsetExpectedError())
      result = row.row.map(r =>
        contractFromRow(
          splitwellCodegen.SplitwellRules.COMPANION
        )(r.acsRow)
      )
    } yield QueryResult(
      row.offset,
      result,
    )
  }

  private def listLaggingContracts[LeaderC, LeaderTCid <: ContractId[
    _
  ], LeaderT, FollowerC, FollowerTCid <: ContractId[_], FollowerT, Id](
      leaderCompanion: LeaderC,
      followerCompanion: FollowerC,
      getLeaderId: LeaderT => Id,
      getFollowerId: FollowerT => Id,
  )(implicit
      leaderCompanionClass: ContractCompanion[LeaderC, LeaderTCid, LeaderT],
      followerCompanionClass: ContractCompanion[FollowerC, FollowerTCid, FollowerT],
      traceContext: TraceContext,
  ) =
    for {
      followerContracts <- multiDomainAcsStore.listAssignedContracts(
        followerCompanion
      )
      leaderContracts <- multiDomainAcsStore.listAssignedContracts(
        leaderCompanion
      )
    } yield {
      val leaderContractsById = leaderContracts.map(c => getLeaderId(c.payload) -> c).toMap
      followerContracts.collect(Function.unlift { c =>
        leaderContractsById
          .get(getFollowerId(c.payload))
          .filter(_.domain != c.domain)
          .map(
            TransferFollowTrigger.Task(_, c)
          )
      })
    }

  override def listLaggingBalanceUpdates()(implicit
      traceContext: TraceContext
  ): Future[Seq[TransferFollowTrigger.Task[
    splitwellCodegen.Group.ContractId,
    splitwellCodegen.Group,
    splitwellCodegen.BalanceUpdate.ContractId,
    splitwellCodegen.BalanceUpdate,
  ]]] =
    listLaggingContracts(
      splitwellCodegen.Group.COMPANION,
      splitwellCodegen.BalanceUpdate.COMPANION,
      _.id,
      _.group.id,
    )

  override def listLaggingGroupInvites()(implicit
      traceContext: TraceContext
  ): Future[Seq[TransferFollowTrigger.Task[
    splitwellCodegen.Group.ContractId,
    splitwellCodegen.Group,
    splitwellCodegen.GroupInvite.ContractId,
    splitwellCodegen.GroupInvite,
  ]]] = listLaggingContracts(
    splitwellCodegen.Group.COMPANION,
    splitwellCodegen.GroupInvite.COMPANION,
    _.id,
    _.group.id,
  )

  override def listLaggingAcceptedGroupInvites()(implicit
      traceContext: TraceContext
  ): Future[Seq[TransferFollowTrigger.Task[
    splitwellCodegen.Group.ContractId,
    splitwellCodegen.Group,
    splitwellCodegen.AcceptedGroupInvite.ContractId,
    splitwellCodegen.AcceptedGroupInvite,
  ]]] = listLaggingContracts(
    splitwellCodegen.Group.COMPANION,
    splitwellCodegen.AcceptedGroupInvite.COMPANION,
    _.id,
    _.groupKey.id,
  )

  override def lookupTransferInProgress(
      paymentRequest: walletCodegen.AppPaymentRequest.ContractId
  )(implicit tc: TraceContext): Future[QueryResult[Option[ContractWithState[
    splitwellCodegen.TransferInProgress.ContractId,
    splitwellCodegen.TransferInProgress,
  ]]]] = waitUntilAcsIngested {
    for {
      row <- storage
        .querySingle(
          selectFromAcsTableWithStateAndOffset(
            SplitwellTables.acsTableName,
            storeId,
            domainMigrationId,
            where = sql"""
              template_id_qualified_name = ${QualifiedName(
                splitwellCodegen.TransferInProgress.TEMPLATE_ID_WITH_PACKAGE_ID
              )} and payment_request_contract_id = ${paymentRequest}
              and assigned_domain is not null
              """,
          ).headOption,
          "lookupTransferInProgress",
        )
        .getOrElse(throw offsetExpectedError())
      result = row.row.map(
        contractWithStateFromRow(
          splitwellCodegen.TransferInProgress.COMPANION
        )(_)
      )
    } yield QueryResult(
      row.offset,
      result,
    )
  }
}
