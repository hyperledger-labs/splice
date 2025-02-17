// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import cats.syntax.either.*
import cats.syntax.traverse.*
import cats.syntax.traverseFilter.*
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.config.{CommunityCryptoConfig, CommunityCryptoProvider}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.{DomainId, UniqueIdentifier}
import com.digitalasset.canton.topology.processing.{EffectiveTime, SequencedTime}
import com.digitalasset.canton.topology.store.{
  StoredTopologyTransaction,
  StoredTopologyTransactions,
}
import StoredTopologyTransaction.GenericStoredTopologyTransaction
import com.digitalasset.canton.topology.transaction.TopologyMapping
import com.digitalasset.canton.topology.transaction.SignedTopologyTransaction.GenericSignedTopologyTransaction
import com.digitalasset.canton.version.ProtocolVersion
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.config.UpgradesConfig
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanSoftDomainMigrationPocAppClient.{
  SynchronizerBootstrappingTransactions,
  SynchronizerIdentities,
}
import org.lfdecentralizedtrust.splice.sv.{ExtraSynchronizerNode, LocalSynchronizerNode}
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import scala.concurrent.{ExecutionContextExecutor, Future}

import InitializeSynchronizerTrigger.Task

class InitializeSynchronizerTrigger(
    override val dsoStore: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
    override protected val context: TriggerContext,
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
      synchronizersWithOnlineNodes <- synchronizerNodes.keys.toList.traverseFilter { prefix =>
        val node = synchronizerNodes(prefix)
        (for {
          sequencerInitialized <- node.sequencerAdminConnection.getStatus.map(
            _.successOption.fold(false)(_.active)
          )
          mediatorInitialized <- node.mediatorAdminConnection.getStatus.map(
            _.successOption.fold(false)(_.active)
          )
        } yield Option.when(!(sequencerInitialized && mediatorInitialized))(prefix)).recover {
          err =>
            logger.info(s"Failed to query sequencer or mediator for $prefix: $err")
            None
        }
      }
      tasks <- synchronizersWithOnlineNodes.traverseFilter { prefix =>
        scanUrls
          .traverse { url =>
            withScanConnection(url) { c =>
              for {
                identities <- c.getSynchronizerIdentities(prefix)
                bootstrapTransactions <- c.getSynchronizerBootstrappingTransactions(prefix)
              } yield (identities, bootstrapTransactions)
            }
          }
          .map(_.unzip)
          .map { case (identities, bootstrapTransactions) =>
            Some(
              Task(
                DomainId(
                  UniqueIdentifier.tryCreate(
                    prefix,
                    dsoStore.key.dsoParty.uid.namespace,
                  )
                ),
                identities,
                NonEmpty
                  .from(bootstrapTransactions)
                  .getOrElse(
                    throw Status.INTERNAL
                      .withDescription("Empty list of scan urls")
                      .asRuntimeException()
                  ),
              )
            )
          }
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
      scanUrls <- getScanUrls()
      decentralizedNamespaceTxs <- getDecentralizedNamespaceDefinitionTransactions(
        participantAdminConnection
      )
      synchronizerIdentities = task.synchronizerIdentities
      bootstrappingStates = task.bootstrappingTransactions
      domainParameters = bootstrappingStates
        .map(_.domainParameters)
        .reduceLeft((a, b) => a.addSignatures(b.signatures.toSeq))
      sequencerDomainState = bootstrappingStates
        .map(_.sequencerDomainState)
        .reduceLeft((a, b) => a.addSignatures(b.signatures.toSeq))
      mediatorDomainState = bootstrappingStates
        .map(_.mediatorDomainState)
        .reduceLeft((a, b) => a.addSignatures(b.signatures.toSeq))
      node = synchronizerNodes(task.synchronizerId.identifier.unwrap)
      bootstrapTransactions = toStoredTopologyBootstrapTransactions(
        decentralizedNamespaceTxs ++
          synchronizerIdentities.flatMap(_.sequencerIdentityTransactions) ++
          synchronizerIdentities.flatMap(_.mediatorIdentityTransactions) ++
          Seq(
            domainParameters,
            sequencerDomainState,
            mediatorDomainState,
          )
      )
      staticDomainParameters = node.parameters
        .toStaticDomainParameters(
          CommunityCryptoConfig(provider = CommunityCryptoProvider.Jce),
          ProtocolVersion.v32,
        )
        .valueOr(err =>
          throw new IllegalArgumentException(s"Invalid domain parameters config: $err")
        )
      _ <- context.retryProvider.ensureThatB(
        RetryFor.Automation,
        "sequencer_initialization",
        "Sequencer is initialized",
        node.sequencerAdminConnection.getStatus.map(_.successOption.fold(false)(_.active)),
        node.sequencerAdminConnection
          .initializeFromBeginning(
            StoredTopologyTransactions(
              bootstrapTransactions
            ),
            staticDomainParameters,
          )
          .map(_ => ()),
        logger,
      )
      _ <- context.retryProvider.ensureThatB(
        RetryFor.Automation,
        "mediator_initialization",
        "Mediator is initialized",
        node.mediatorAdminConnection.getStatus.map(_.successOption.fold(false)(_.active)),
        node.mediatorAdminConnection
          .initialize(
            task.synchronizerId,
            LocalSynchronizerNode.toSequencerConnection(node.sequencerPublicApi),
            node.mediatorSequencerAmplification,
          )
          .map(_ => ()),
        logger,
      )
    } yield TaskSuccess(s"Initialized synchronizer ${task.synchronizerId}")
  }

  protected def isStaleTask(task: Task)(implicit tc: TraceContext): Future[Boolean] = {
    val node = synchronizerNodes(task.synchronizerId.identifier.unwrap)
    for {
      sequencerInitialized <- node.sequencerAdminConnection.getStatus.map(
        _.successOption.fold(false)(_.active)
      )
      mediatorInitialized <- node.mediatorAdminConnection.getStatus.map(
        _.successOption.fold(false)(_.active)
      )
    } yield sequencerInitialized && mediatorInitialized
  }

  // Takes a list of (ordered) signed topology transactions and turns them into
  // StoredTopologyTransactions ensuring that only the latest serial has validUntil = None
  private def toStoredTopologyBootstrapTransactions(
      ts: Seq[GenericSignedTopologyTransaction]
  ): Seq[GenericStoredTopologyTransaction] =
    ts.foldRight(
      (Set.empty[TopologyMapping.MappingHash], Seq.empty[GenericStoredTopologyTransaction])
    ) { case (tx, (newerMappings, acc)) =>
      (
        newerMappings + tx.transaction.mapping.uniqueKey,
        StoredTopologyTransaction(
          SequencedTime(CantonTimestamp.MinValue.immediateSuccessor),
          EffectiveTime(CantonTimestamp.MinValue.immediateSuccessor),
          Option.when(newerMappings.contains(tx.transaction.mapping.uniqueKey))(
            EffectiveTime(CantonTimestamp.MinValue.immediateSuccessor)
          ),
          tx.copy(isProposal = false),
        ) +: acc,
      )
    }._2
}

object InitializeSynchronizerTrigger {
  final case class Task(
      synchronizerId: DomainId,
      synchronizerIdentities: Seq[SynchronizerIdentities],
      bootstrappingTransactions: NonEmpty[Seq[SynchronizerBootstrappingTransactions]],
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
