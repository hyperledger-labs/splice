package com.daml.network.environment

import com.digitalasset.canton.admin.api.client.commands.{
  EnterpriseMediatorAdministrationCommands,
  StatusAdminCommands,
  TopologyAdminCommandsX,
}
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.health.admin.data.{MediatorNodeStatus, NodeStatus}
import com.digitalasset.canton.protocol.StaticDomainParameters
import com.digitalasset.canton.sequencing.{SequencerConnection, SequencerConnections}
import com.digitalasset.canton.topology.{DomainId, MediatorId}
import com.digitalasset.canton.topology.admin.grpc.BaseQueryX
import com.digitalasset.canton.topology.store.TimeQueryX
import com.digitalasset.canton.topology.transaction.MediatorDomainStateX
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.digitalasset.canton.topology.transaction.TopologyMappingX.Code.{
  NamespaceDelegationX,
  OwnerToKeyMappingX,
}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Connection to the subset of the Canton mediator admin API that we rely
  * on in our own applications.
  */
class MediatorAdminConnection(
    config: ClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends AppConnection(
      config,
      timeouts,
      loggerFactory,
      // The version endpoint is only injected into our own apps so we cannot run this against the admin .
      enableVersionCompatCheck = false,
    ) {

  override val serviceName = "Canton Mediator Admin API"

  private val mediatorStatusCommand =
    new StatusAdminCommands.GetStatus(MediatorNodeStatus.fromProtoV0)

  def getStatus(implicit traceContext: TraceContext): Future[NodeStatus[MediatorNodeStatus]] =
    runCmd(
      mediatorStatusCommand
    )

  def getMediatorId(implicit traceContext: TraceContext): Future[MediatorId] =
    runCmd(
      TopologyAdminCommandsX.Init.GetId()
    ).map(MediatorId(_))

  def getMediatorIdentityTransactions(
      id: MediatorId
  )(implicit traceContext: TraceContext): Future[Seq[GenericSignedTopologyTransactionX]] =
    runCmd(
      TopologyAdminCommandsX.Read.ListAll(
        BaseQueryX(
          filterStore = "",
          proposals = false,
          timeQuery = TimeQueryX.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        )
      )
    ).map { transactions =>
      transactions.result
        .map(_.transaction)
        .filter(tx =>
          Set(NamespaceDelegationX, OwnerToKeyMappingX).contains(
            tx.transaction.mapping.code
          ) && (tx.transaction.mapping.maybeUid.contains(
            id.uid
          ) || tx.transaction.mapping.namespace == id.uid.namespace)
        )
    }

  def getMediatorState(domainId: DomainId)(implicit
      traceContext: TraceContext
  ): Future[MediatorDomainStateX] =
    runCmd(
      TopologyAdminCommandsX.Read.MediatorDomainState(
        BaseQueryX(
          filterStore = domainId.filterString,
          proposals = false,
          timeQuery = TimeQueryX.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterDomain = "",
      )
    ).map { txs =>
      txs.headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(s"No mediator state for domain $domainId")
            .asRuntimeException()
        )
        .item
    }

  def initialize(
      domainId: DomainId,
      domainParameters: StaticDomainParameters,
      sequencerConnection: SequencerConnection,
  )(implicit traceContext: TraceContext): Future[Unit] =
    runCmd(
      EnterpriseMediatorAdministrationCommands.InitializeX(
        domainId,
        domainParameters,
        SequencerConnections.default(sequencerConnection),
      )
    )
}
