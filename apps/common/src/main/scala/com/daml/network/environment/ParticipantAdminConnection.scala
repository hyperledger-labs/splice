package com.daml.network.environment

import com.digitalasset.canton.{DomainAlias, DiscardOps}
import com.digitalasset.canton.admin.api.client.commands.{
  ParticipantAdminCommands,
  TopologyAdminCommands,
  TopologyAdminCommandsX,
}
import com.digitalasset.canton.admin.api.client.data.{
  ListConnectedDomainsResult,
  ListPartyToParticipantResult,
}
import com.digitalasset.canton.admin.api.client.data.topologyx.{
  ListPartyToParticipantResult as ListPartyToParticipantResultX
}
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, PositiveInt}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.admin.v0.AcsSnapshotChunk
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.topology.{
  DomainId,
  MediatorId,
  ParticipantId,
  PartyId,
  SequencerId,
  UniqueIdentifier,
}
import com.digitalasset.canton.topology.admin.grpc.{BaseQuery, BaseQueryX}
import com.digitalasset.canton.topology.store.{TimeQuery, TimeQueryX}
import com.digitalasset.canton.topology.transaction.{
  HostingParticipant,
  MediatorDomainStateX,
  ParticipantPermission,
  ParticipantPermissionX,
  PartyToParticipantX,
  RequestSide,
  SequencerDomainStateX,
  TopologyChangeOp,
  TopologyChangeOpX,
}
import com.digitalasset.canton.topology.transaction.TopologyMappingX.Code.{
  NamespaceDelegationX,
  OwnerToKeyMappingX,
}
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}

/** Connection to the subset of the Canton admin API that we rely
  * on in our own applications.
  */
class ParticipantAdminConnection(
    config: ClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends AppConnection(
      config,
      timeouts,
      loggerFactory,
      // The version endpoint is only injected into our own apps so we cannot run this against the admin API.
      enableVersionCompatCheck = false,
    ) {
  override val serviceName = "Canton Participant Admin API"

  private def listConnectedDomains()(implicit
      traceContext: TraceContext
  ): Future[Seq[ListConnectedDomainsResult]] = {
    runCmd(ParticipantAdminCommands.DomainConnectivity.ListConnectedDomains())
  }

  def reconnectAllDomains()(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    runCmd(ParticipantAdminCommands.DomainConnectivity.ReconnectDomains(ignoreFailures = false))
  }

  def disconnectFromAllDomains()(implicit
      traceContext: TraceContext
  ): Future[Unit] = for {
    domains <- listConnectedDomains()
    _ <- Future.sequence(
      domains.map(domain =>
        runCmd(ParticipantAdminCommands.DomainConnectivity.DisconnectDomain(domain.domainAlias))
      )
    )
  } yield ()

  def authorizePartyToParticipant(
      ops: TopologyChangeOp,
      party: PartyId,
      participant: ParticipantId,
      side: RequestSide,
      permission: ParticipantPermission,
  )(implicit traceContext: TraceContext): Future[Unit] =
    runCmd(
      TopologyAdminCommands.Write.AuthorizePartyToParticipant(
        ops,
        None,
        side,
        party,
        participant,
        permission,
        replaceExisting = true,
        force = false,
      )
    ).map(_ => ())

  def authorizePartyToParticipantX(
      party: PartyId,
      existingParticipants: Seq[ParticipantId],
      newParticipant: ParticipantId,
      authorizingParticipant: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Unit] =
    runCmd(
      TopologyAdminCommandsX.Write.Propose(
        mapping = PartyToParticipantX(
          party,
          None,
          1, // Increase this to switch to a real consortium party
          (newParticipant +: existingParticipants).map(
            HostingParticipant(
              _,
              ParticipantPermissionX.Submission,
            )
          ),
          groupAddressing = false,
        ),
        signedBy = Seq(authorizingParticipant.uid.namespace.fingerprint),
        serial = None,
      )
    ).map(_ => ())

  def listPartyToParticipantMappings(
      filterStore: String = "",
      operation: Option[TopologyChangeOp] = None,
      filterParty: String = "",
      filterParticipant: String = "",
      filterRequestSide: Option[RequestSide] = None,
      filterPermission: Option[ParticipantPermission] = None,
  )(implicit traceContext: TraceContext): Future[Seq[ListPartyToParticipantResult]] = {
    runCmd(
      TopologyAdminCommands.Read.ListPartyToParticipant(
        BaseQuery(
          filterStore,
          useStateStore = true,
          TimeQuery.HeadState,
          operation,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterParty,
        filterParticipant,
        filterRequestSide,
        filterPermission,
      )
    )
  }

  def listPartyToParticipantMappingsX(
      filterStore: String = "",
      operation: Option[TopologyChangeOpX] = None,
      filterParty: String = "",
      filterParticipant: String = "",
      timeQuery: TimeQueryX = TimeQueryX.HeadState,
  )(implicit traceContext: TraceContext): Future[Seq[ListPartyToParticipantResultX]] = {
    runCmd(
      TopologyAdminCommandsX.Read.ListPartyToParticipant(
        BaseQueryX(
          filterStore,
          proposals = false,
          timeQuery,
          operation,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterParty,
        filterParticipant,
      )
    )
  }

  def downloadAcsSnapshot(
      parties: Set[PartyId],
      filterDomainId: String = "",
      timestamp: Option[Instant] = None,
      chunkSize: Option[PositiveInt] = None,
  )(implicit traceContext: TraceContext): Future[ByteString] = {
    val requestComplete = Promise[ByteString]()
    // TODO(#3298) just concatenate the byteString here. Make it scale to 2M contracts.
    val observer = new GrpcByteChunksToByteArrayObserver[AcsSnapshotChunk](requestComplete)
    runCmd(
      ParticipantAdminCommands.ParticipantRepairManagement.Download(
        parties,
        filterDomainId,
        timestamp,
        None,
        chunkSize,
        observer,
        gzipFormat = false,
      )
    ).discard
    requestComplete.future
  }

  def uploadAcsSnapshot(acsBytes: ByteString)(implicit traceContext: TraceContext): Future[Unit] = {
    runCmd(
      ParticipantAdminCommands.ParticipantRepairManagement.Upload(acsBytes)
    )
  }

  def getParticipantId(
      useXNodes: Boolean
  )(implicit traceContext: TraceContext): Future[ParticipantId] =
    if (useXNodes) {
      runCmd(
        TopologyAdminCommandsX.Init.GetId()
      ).map(ParticipantId(_))
    } else {
      runCmd(
        TopologyAdminCommands.Init.GetId()
      ).map(ParticipantId(_))
    }

  def latestSequencerDomainStateX(
      domainId: DomainId
  )(implicit traceContext: TraceContext): Future[SequencerDomainStateX] =
    runCmd(
      TopologyAdminCommandsX.Read.ListAll(
        BaseQueryX(
          filterStore = domainId.filterString,
          proposals = false,
          timeQuery = TimeQueryX.HeadState,
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        )
      )
    ).map { transactions =>
      transactions
        .collectOfMapping[SequencerDomainStateX]
        .result
        .headOption
        .getOrElse(
          throw Status.NOT_FOUND
            .withDescription(s"No sequencer state for domain $domainId")
            .asRuntimeException()
        )
        .transaction
        .transaction
        .mapping
    }

  def addTopologyTransactions(txs: Seq[GenericSignedTopologyTransactionX])(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(
      TopologyAdminCommandsX.Write.AddTransactions(txs)
    )

  def proposeSequencers(
      domainId: DomainId,
      threshold: PositiveInt,
      active: Seq[SequencerId],
      passive: Seq[SequencerId] = Seq.empty,
      signedBy: Option[Fingerprint] = None,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, SequencerDomainStateX]] =
    runCmd(
      TopologyAdminCommandsX.Write.Propose(
        SequencerDomainStateX
          .create(domain = domainId, threshold = threshold, active = active, observers = passive),
        signedBy.toList,
        None,
      )
    )

  def proposeMediators(
      domainId: DomainId,
      group: NonNegativeInt,
      threshold: PositiveInt,
      active: Seq[MediatorId],
      passive: Seq[MediatorId] = Seq.empty,
      signedBy: Option[Fingerprint] = None,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransactionX[TopologyChangeOpX, MediatorDomainStateX]] =
    runCmd(
      TopologyAdminCommandsX.Write.Propose(
        MediatorDomainStateX
          .create(
            domain = domainId,
            group = group,
            threshold = threshold,
            active = active,
            observers = passive,
          ),
        signedBy.toList,
        None,
      )
    )

  def getDomainConnectionConfig(
      domain: DomainAlias
  )(implicit traceContext: TraceContext): Future[DomainConnectionConfig] =
    for {
      configuredDomains <- runCmd(ParticipantAdminCommands.DomainConnectivity.ListConfiguredDomains)
    } yield configuredDomains
      .collectFirst {
        case (config, _) if config.domain == domain => config
      }
      .getOrElse(
        throw Status.NOT_FOUND
          .withDescription(s"Domain $domain is not configured on the participant")
          .asRuntimeException()
      )

  def setDomainConnectionConfig(config: DomainConnectionConfig)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(
      ParticipantAdminCommands.DomainConnectivity.ModifyDomainConnection(config)
    )

  def modifyDomainConnectionConfig(
      domain: DomainAlias,
      f: DomainConnectionConfig => DomainConnectionConfig,
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      oldConfig <- getDomainConnectionConfig(domain)
      newConfig = f(oldConfig)
      _ <- setDomainConnectionConfig(newConfig)
    } yield ()

  def getIdentityTransactions(
      domainId: DomainId,
      id: UniqueIdentifier,
  )(implicit traceContext: TraceContext): Future[Seq[GenericSignedTopologyTransactionX]] =
    runCmd(
      TopologyAdminCommandsX.Read.ListAll(
        BaseQueryX(
          filterStore = domainId.filterString,
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
            id
          ) || tx.transaction.mapping.namespace == id.namespace)
        )
    }
}
