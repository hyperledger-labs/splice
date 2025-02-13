// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import cats.syntax.either.*
import cats.syntax.traverse.*
import cats.syntax.traverseFilter.*
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.{DomainId, ForceFlag, UniqueIdentifier}
import com.digitalasset.canton.topology.store.{TimeQuery, TopologyStoreId}
import com.digitalasset.canton.topology.transaction.{
  DomainParametersState,
  MediatorDomainState,
  SequencerDomainState,
}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.config.{Thresholds, UpgradesConfig}
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanSoftDomainMigrationPocAppClient.SynchronizerIdentities
import org.lfdecentralizedtrust.splice.sv.{ExtraSynchronizerNode, LocalSynchronizerNode}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import scala.concurrent.{ExecutionContextExecutor, Future}

import SignSynchronizerBootstrappingState.Task

class SignSynchronizerBootstrappingStateTrigger(
    override val dsoStore: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
    override protected val context: TriggerContext,
    existingSynchronizer: LocalSynchronizerNode,
    synchronizerNodes: Map[String, ExtraSynchronizerNode],
    override val upgradesConfig: UpgradesConfig,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpClient,
    mat: Materializer,
    templateJsonDecoder: TemplateJsonDecoder,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[Task]
    with SoftMigrationTrigger {

  protected def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] =
    for {
      scanUrls <- getScanUrls()
      // TODO(#17032): Handle failures properly
      // TODO(#17032): consider whether we only want to initialize the ones in requiredSynchronizers in AmuletConfig
      // TODO(#17032): Exclude synchronizers already bootstrapped
      tasks <- synchronizerNodes.keys.toList.traverseFilter { prefix =>
        scanUrls
          .traverse { url => withScanConnection(url)(_.getSynchronizerIdentities(prefix)) }
          .map(identities =>
            Some(
              Task(
                DomainId(
                  UniqueIdentifier.tryCreate(
                    prefix,
                    dsoStore.key.dsoParty.uid.namespace,
                  )
                ),
                identities,
              )
            )
          )
          .recover { err =>
            // TODO(#17032) Include the url of the failed SV
            logger.info(
              s"Failed to query synchronizer identities for $prefix, not all SVs are ready yet: $err"
            )
            None
          }
      }
    } yield tasks

  protected def completeTask(task: Task)(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      decentralizedSynchronizerId <- dsoStore.getAmuletRulesDomain()(tc)
      // for now we just copy the parameters from the existing domain.
      parameters <- existingSynchronizer.sequencerAdminConnection.getDomainParametersState(
        decentralizedSynchronizerId
      )
      domainParameters = DomainParametersState(
        task.synchronizerId,
        parameters.mapping.parameters,
      )
      sequencerDomainState = SequencerDomainState
        .create(
          task.synchronizerId,
          Thresholds.sequencerConnectionsSizeThreshold(task.sequencerIds.size),
          task.sequencerIds,
          Seq.empty,
        )
        .valueOr(err =>
          throw Status.INTERNAL
            .withDescription(s"Failed to construct SequencerDomainState: $err")
            .asRuntimeException
        )
      mediatorDomainState = MediatorDomainState
        .create(
          task.synchronizerId,
          NonNegativeInt.zero,
          Thresholds.mediatorDomainStateThreshold(task.mediatorIds.size),
          task.mediatorIds,
          Seq.empty,
        )
        .valueOr(err =>
          throw Status.INTERNAL
            .withDescription(s"Failed to construct MediatorDomainState: $err")
            .asRuntimeException
        )
      participantId <- participantAdminConnection.getParticipantId()
      decentralizedNamespaceTxs <- getDecentralizedNamespaceDefinitionTransactions(
        participantAdminConnection
      )
      _ <- participantAdminConnection.addTopologyTransactions(
        TopologyStoreId.AuthorizedStore,
        decentralizedNamespaceTxs,
        ForceFlag.AlienMember,
      )
      signedBy = participantId.uid.namespace.fingerprint
      _ <- context.retryProvider.ensureThatB(
        RetryFor.ClientCalls,
        "domain_parameters",
        "domain parameters are signed",
        for {
          proposalsExist <- participantAdminConnection
            .listDomainParametersState(
              TopologyStoreId.AuthorizedStore,
              task.synchronizerId,
              TopologyTransactionType.AllProposals,
              TimeQuery.HeadState,
            )
            .map(_.nonEmpty)
          authorizedExist <-
            participantAdminConnection
              .listDomainParametersState(
                TopologyStoreId.AuthorizedStore,
                task.synchronizerId,
                TopologyTransactionType.AuthorizedState,
                TimeQuery.HeadState,
              )
              .map(_.nonEmpty)
        } yield proposalsExist || authorizedExist,
        participantAdminConnection
          .proposeMapping(
            TopologyStoreId.AuthorizedStore,
            domainParameters,
            serial = PositiveInt.one,
            isProposal = true,
          )
          .map(_ => ()),
        logger,
      )
      _ <- participantAdminConnection.addTopologyTransactions(
        TopologyStoreId.AuthorizedStore,
        task.synchronizerIdentities.flatMap(_.sequencerIdentityTransactions),
        ForceFlag.AlienMember,
      )
      _ <- context.retryProvider.ensureThatB(
        RetryFor.ClientCalls,
        "sequencer_domain_state",
        "sequencer domain state is signed",
        for {
          proposalsExist <- participantAdminConnection
            .listSequencerDomainState(
              TopologyStoreId.AuthorizedStore,
              task.synchronizerId,
              TimeQuery.HeadState,
              true,
            )
            .map(_.nonEmpty)
          authorizedExist <-
            participantAdminConnection
              .listSequencerDomainState(
                TopologyStoreId.AuthorizedStore,
                task.synchronizerId,
                TimeQuery.HeadState,
                false,
              )
              .map(_.nonEmpty)
        } yield proposalsExist || authorizedExist,
        participantAdminConnection
          .proposeMapping(
            TopologyStoreId.AuthorizedStore,
            sequencerDomainState,
            serial = PositiveInt.one,
            isProposal = true,
          )
          .map(_ => ()),
        logger,
      )
      // add mediator keys, note that in 3.0 not adding these does not fail but in 3.1 it will
      _ <- participantAdminConnection.addTopologyTransactions(
        TopologyStoreId.AuthorizedStore,
        task.synchronizerIdentities.flatMap(_.mediatorIdentityTransactions),
        ForceFlag.AlienMember,
      )
      _ <- context.retryProvider.ensureThatB(
        RetryFor.ClientCalls,
        "mediator_domain_state",
        "mediator domain state is signed",
        for {
          proposalsExist <- participantAdminConnection
            .listMediatorDomainState(
              TopologyStoreId.AuthorizedStore,
              task.synchronizerId,
              true,
            )
            .map(_.nonEmpty)
          authorizedExist <-
            participantAdminConnection
              .listMediatorDomainState(
                TopologyStoreId.AuthorizedStore,
                task.synchronizerId,
                false,
              )
              .map(_.nonEmpty)
        } yield proposalsExist || authorizedExist,
        participantAdminConnection
          .proposeMapping(
            TopologyStoreId.AuthorizedStore,
            mediatorDomainState,
            serial = PositiveInt.one,
            isProposal = true,
          )
          .map(_ => ()),
        logger,
      )
    } yield TaskSuccess(s"Signed topology state for ${task.synchronizerId}")
  }
  // TODO(#17032) Add a better staleness check
  protected def isStaleTask(task: Task)(implicit tc: TraceContext): Future[Boolean] =
    Future.successful(false)
}

object SignSynchronizerBootstrappingState {
  final case class Task(
      synchronizerId: DomainId,
      synchronizerIdentities: Seq[SynchronizerIdentities],
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = prettyOfClass(
      param("synchronizerId", _.synchronizerId),
      param("sequencerIds", _.sequencerIds),
      param("mediatorIds", _.mediatorIds),
    )

    lazy val sequencerIds = synchronizerIdentities.map(_.sequencerId)
    lazy val mediatorIds = synchronizerIdentities.map(_.mediatorId)
  }
}
