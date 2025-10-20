// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.splitwell.store

import org.lfdecentralizedtrust.splice.automation.TransferFollowTrigger
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell as splitwellCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as walletCodegen
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.splitwell.config.SplitwellSynchronizerConfig
import org.lfdecentralizedtrust.splice.splitwell.store.db.DbSplitwellStore
import org.lfdecentralizedtrust.splice.splitwell.store.db.SplitwellTables.SplitwellAcsStoreRowData
import org.lfdecentralizedtrust.splice.store.{AppStore, MultiDomainAcsStore}
import org.lfdecentralizedtrust.splice.util.{
  AssignedContract,
  Contract,
  ContractWithState,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.db.AcsInterfaceViewRowData

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

trait SplitwellStore extends AppStore {
  import MultiDomainAcsStore.QueryResult

  /** The key identifying the parties considered by this store. */
  val key: SplitwellStore.Key

  protected[this] def domainConfig: SplitwellSynchronizerConfig

  private[this] final def defaultAcsDomain = domainConfig.splitwell.preferred.alias

  private[splitwell] final def defaultAcsSynchronizerIdF(implicit
      tc: TraceContext
  ): Future[SynchronizerId] =
    domains.waitForDomainConnection(defaultAcsDomain)

  override def multiDomainAcsStore: MultiDomainAcsStore

  def lookupInstallWithOffset(
      synchronizerId: SynchronizerId,
      user: PartyId,
  )(implicit tc: TraceContext): Future[QueryResult[Option[
    Contract[splitwellCodegen.SplitwellInstall.ContractId, splitwellCodegen.SplitwellInstall]
  ]]]

  def lookupGroupWithOffset(
      owner: PartyId,
      id: splitwellCodegen.GroupId,
  )(implicit tc: TraceContext): Future[
    QueryResult[
      Option[ContractWithState[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]]
    ]
  ]

  def listGroups(
      user: PartyId
  )(implicit
      traceContext: TraceContext
  ): Future[Seq[ContractWithState[splitwellCodegen.Group.ContractId, splitwellCodegen.Group]]]

  def listGroupInvites(owner: PartyId)(implicit traceContext: TraceContext): Future[
    Seq[ContractWithState[splitwellCodegen.GroupInvite.ContractId, splitwellCodegen.GroupInvite]]
  ]

  def listAcceptedGroupInvites(owner: PartyId, groupId: String)(implicit
      traceContext: TraceContext
  ): Future[Seq[ContractWithState[
    splitwellCodegen.AcceptedGroupInvite.ContractId,
    splitwellCodegen.AcceptedGroupInvite,
  ]]]

  def listBalanceUpdates(user: PartyId, key: splitwellCodegen.GroupKey)(implicit
      traceContext: TraceContext
  ): Future[Seq[
    ContractWithState[splitwellCodegen.BalanceUpdate.ContractId, splitwellCodegen.BalanceUpdate]
  ]]

  /** Contract IDs of groups that can be transferred from
    * [[org.lfdecentralizedtrust.splice.splitwell.config.SplitwellDomains#others]] because all
    * of their members are installed on
    * [[org.lfdecentralizedtrust.splice.splitwell.config.SplitwellDomains#preferred]], grouped
    * by the domain they are currently installed on.
    *
    * @note Invariant: every key is an ID associated with `others`
    * @note Invariant: every value is non-empty
    */
  def listTransferrableGroups()(implicit
      tc: TraceContext
  ): Future[Map[SynchronizerId, Seq[splitwellCodegen.Group.ContractId]]]

  def listSplitwellInstalls(user: PartyId)(implicit traceContext: TraceContext): Future[Seq[
    AssignedContract[
      splitwellCodegen.SplitwellInstall.ContractId,
      splitwellCodegen.SplitwellInstall,
    ]
  ]]

  def listSplitwellRules()(implicit traceContext: TraceContext): Future[Seq[
    AssignedContract[
      splitwellCodegen.SplitwellRules.ContractId,
      splitwellCodegen.SplitwellRules,
    ]
  ]]

  def lookupSplitwellRules(
      synchronizerId: SynchronizerId
  )(implicit tc: TraceContext): Future[QueryResult[Option[
    Contract[
      splitwellCodegen.SplitwellRules.ContractId,
      splitwellCodegen.SplitwellRules,
    ]
  ]]]

  /** List balance updates that are lagging behind the corresponding group contract meaning the
    * have not yet transferred to the same domain.
    */
  def listLaggingBalanceUpdates()(implicit
      traceContext: TraceContext
  ): Future[Seq[TransferFollowTrigger.Task[
    splitwellCodegen.Group.ContractId,
    splitwellCodegen.Group,
    splitwellCodegen.BalanceUpdate.ContractId,
    splitwellCodegen.BalanceUpdate,
  ]]]

  /** List group invites that are lagging behind the corresponding group contract meaning the
    * have not yet transferred to the same domain.
    */
  def listLaggingGroupInvites()(implicit
      traceContext: TraceContext
  ): Future[Seq[TransferFollowTrigger.Task[
    splitwellCodegen.Group.ContractId,
    splitwellCodegen.Group,
    splitwellCodegen.GroupInvite.ContractId,
    splitwellCodegen.GroupInvite,
  ]]]

  /** List accepted group invites that are lagging behind the corresponding group contract meaning the
    * have not yet transferred to the same domain.
    */
  def listLaggingAcceptedGroupInvites()(implicit
      traceContext: TraceContext
  ): Future[Seq[TransferFollowTrigger.Task[
    splitwellCodegen.Group.ContractId,
    splitwellCodegen.Group,
    splitwellCodegen.AcceptedGroupInvite.ContractId,
    splitwellCodegen.AcceptedGroupInvite,
  ]]]

  def lookupTransferInProgress(
      paymentRequest: walletCodegen.AppPaymentRequest.ContractId
  )(implicit tc: TraceContext): Future[QueryResult[Option[ContractWithState[
    splitwellCodegen.TransferInProgress.ContractId,
    splitwellCodegen.TransferInProgress,
  ]]]]

  protected[this] def groupMembers(group: splitwellCodegen.Group): Set[String] =
    group.members.asScala.toSet + group.owner

  protected[this] def groupKey(owner: PartyId, id: String): splitwellCodegen.GroupKey =
    new splitwellCodegen.GroupKey(
      owner.toProtoPrimitive,
      key.providerParty.toProtoPrimitive,
      new splitwellCodegen.GroupId(id),
    )

  protected[this] def groupKey(group: splitwellCodegen.Group): splitwellCodegen.GroupKey =
    new splitwellCodegen.GroupKey(
      group.owner,
      group.provider,
      group.id,
    )
}

object SplitwellStore {
  def apply(
      key: Key,
      storage: DbStorage,
      domainConfig: SplitwellSynchronizerConfig,
      loggerFactory: NamedLoggerFactory,
      retryProvider: RetryProvider,
      domainMigrationInfo: DomainMigrationInfo,
      participantId: ParticipantId,
  )(implicit
      ec: ExecutionContext,
      templateJsonDecoder: TemplateJsonDecoder,
      close: CloseContext,
  ): SplitwellStore =
    new DbSplitwellStore(
      key,
      domainConfig,
      storage,
      loggerFactory,
      retryProvider,
      domainMigrationInfo,
      participantId,
    )

  case class Key(
      providerParty: PartyId
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("providerParty", _.providerParty)
    )
  }

  def contractFilter(key: Key): MultiDomainAcsStore.ContractFilter[
    SplitwellAcsStoreRowData,
    AcsInterfaceViewRowData.NoInterfacesIngested,
  ] = {
    import MultiDomainAcsStore.mkFilter
    val provider = key.providerParty.toProtoPrimitive

    MultiDomainAcsStore.SimpleContractFilter(
      key.providerParty,
      Map(
        mkFilter(splitwellCodegen.SplitwellRules.COMPANION)(co => co.payload.provider == provider) {
          contract =>
            SplitwellAcsStoreRowData(
              contract = contract
            )
        },
        mkFilter(splitwellCodegen.SplitwellInstallRequest.COMPANION)(co =>
          co.payload.provider == provider
        ) { contract =>
          SplitwellAcsStoreRowData(
            contract = contract,
            installUser = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
          )
        },
        mkFilter(splitwellCodegen.SplitwellInstall.COMPANION)(co =>
          co.payload.provider == provider
        ) { contract =>
          SplitwellAcsStoreRowData(
            contract = contract,
            installUser = Some(PartyId.tryFromProtoPrimitive(contract.payload.user)),
          )
        },
        mkFilter(splitwellCodegen.TransferInProgress.COMPANION)(co =>
          co.payload.group.provider == provider
        ) { contract =>
          SplitwellAcsStoreRowData(
            contract = contract,
            paymentRequestCid = Some(contract.payload.reference),
          )
        },
        mkFilter(splitwellCodegen.Group.COMPANION)(co => co.payload.provider == provider) {
          contract =>
            SplitwellAcsStoreRowData(
              contract = contract,
              groupId = Some(contract.payload.id.unpack),
              groupOwner = Some(PartyId.tryFromProtoPrimitive(contract.payload.owner)),
            )
        },
        mkFilter(splitwellCodegen.GroupRequest.COMPANION)(co =>
          co.payload.group.provider == provider
        ) { contract =>
          SplitwellAcsStoreRowData(
            contract = contract,
            groupId = Some(contract.payload.group.id.unpack),
            groupOwner = Some(PartyId.tryFromProtoPrimitive(contract.payload.group.owner)),
          )
        },
        mkFilter(splitwellCodegen.GroupInvite.COMPANION)(co =>
          co.payload.group.provider == provider
        ) { contract =>
          SplitwellAcsStoreRowData(
            contract = contract,
            groupId = Some(contract.payload.group.id.unpack),
            groupOwner = Some(PartyId.tryFromProtoPrimitive(contract.payload.group.owner)),
          )
        },
        mkFilter(splitwellCodegen.AcceptedGroupInvite.COMPANION)(co =>
          co.payload.groupKey.provider == provider
        ) { contract =>
          SplitwellAcsStoreRowData(
            contract = contract,
            groupId = Some(contract.payload.groupKey.id.unpack),
            groupOwner = Some(PartyId.tryFromProtoPrimitive(contract.payload.groupKey.owner)),
          )
        },
        mkFilter(splitwellCodegen.BalanceUpdate.COMPANION)(co =>
          co.payload.group.provider == provider
        ) { contract =>
          SplitwellAcsStoreRowData(
            contract = contract,
            groupId = Some(contract.payload.group.id.unpack),
            groupOwner = Some(PartyId.tryFromProtoPrimitive(contract.payload.group.owner)),
          )
        },
        mkFilter(walletCodegen.AcceptedAppPayment.COMPANION)(co =>
          co.payload.provider == provider
        ) { contract =>
          SplitwellAcsStoreRowData(
            contract = contract
          )
        },
        mkFilter(walletCodegen.TerminatedAppPayment.COMPANION)(co =>
          co.payload.provider == provider
        ) { contract =>
          SplitwellAcsStoreRowData(
            contract = contract
          )
        },
      ),
    )
  }
}
