package com.daml.network.environment

import com.daml.network.admin.api.client.GrpcClientMetrics
import com.digitalasset.canton.admin.api.client.commands.{
  EnterpriseSequencerAdminCommands,
  SequencerAdminCommands,
  StatusAdminCommands,
}
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.domain.sequencing.admin.grpc.InitializeSequencerResponse
import com.digitalasset.canton.domain.sequencing.sequencer.SequencerPruningStatus
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.health.admin.data.{NodeStatus, SequencerNodeStatus}
import com.digitalasset.canton.protocol.StaticDomainParameters
import com.digitalasset.canton.topology.{Member, NodeIdentity, SequencerId}
import com.digitalasset.canton.topology.store.StoredTopologyTransactionsX
import StoredTopologyTransactionsX.GenericStoredTopologyTransactionsX
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, PositiveInt}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.traffic.MemberTrafficStatus
import com.google.protobuf.ByteString
import io.grpc.Status

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Connection to the subset of the Canton sequencer admin API that we rely
  * on in our own applications.
  */
class SequencerAdminConnection(
    config: ClientConfig,
    apiLoggingConfig: ApiLoggingConfig,
    loggerFactory: NamedLoggerFactory,
    grpcClientMetrics: GrpcClientMetrics,
    retryProvider: RetryProvider,
)(implicit ec: ExecutionContextExecutor)
    extends TopologyAdminConnection(
      config,
      apiLoggingConfig,
      loggerFactory,
      grpcClientMetrics,
      retryProvider,
    ) {

  override val serviceName = "Canton Sequencer Admin API"

  private val sequencerStatusCommand =
    new StatusAdminCommands.GetStatus(SequencerNodeStatus.fromProtoV30)

  def getStatus(implicit traceContext: TraceContext): Future[NodeStatus[SequencerNodeStatus]] =
    runCmd(
      sequencerStatusCommand
    )

  def getSequencerId(implicit traceContext: TraceContext): Future[SequencerId] =
    getId().map(SequencerId(_))

  def getOnboardingState(sequencerId: SequencerId)(implicit
      traceContext: TraceContext
  ): Future[ByteString] =
    runCmd(
      EnterpriseSequencerAdminCommands.OnboardingState(Left(sequencerId))
    )

  def initializeFromGenesisState(
      topologySnapshot: GenericStoredTopologyTransactionsX,
      domainParameters: StaticDomainParameters,
  )(implicit traceContext: TraceContext): Future[InitializeSequencerResponse] =
    runCmd(
      EnterpriseSequencerAdminCommands.InitializeFromGenesisState(
        // TODO(#10953) Stop doing that.
        topologySnapshot.toByteString(domainParameters.protocolVersion),
        domainParameters,
      )
    )

  def initializeFromOnboardingState(
      onboardingState: ByteString
  )(implicit traceContext: TraceContext): Future[InitializeSequencerResponse] =
    runCmd(
      EnterpriseSequencerAdminCommands.InitializeFromOnboardingState(
        onboardingState
      )
    )

  def listSequencerTrafficControlState(filterMembers: Seq[Member] = Seq.empty)(implicit
      traceContext: TraceContext
  ): Future[Seq[TrafficStatus]] =
    runCmd(
      SequencerAdminCommands.GetTrafficControlState(filterMembers)
    ).map(_.members.map(TrafficStatus))

  def getSequencerTrafficControlState(
      member: Member
  )(implicit traceContext: TraceContext): Future[TrafficStatus] = {
    lookupSequencerTrafficControlState(member).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(s"No traffic state found for member ${member}")
          .asRuntimeException()
      )
    )
  }

  def lookupSequencerTrafficControlState(
      member: Member
  )(implicit traceContext: TraceContext): Future[Option[TrafficStatus]] = {
    listSequencerTrafficControlState(Seq(member)).map {
      case Seq() => None
      case Seq(m) => Some(m)
      case memberList =>
        throw Status.INTERNAL
          .withDescription(
            s"Received more than one traffic status response for member ${member}: ${memberList}"
          )
          .asRuntimeException()
    }
  }

  private def setTrafficControlState(
      member: Member,
      newTotalExtraTrafficLimit: NonNegativeLong,
      serial: PositiveInt,
  )(implicit traceContext: TraceContext): Future[Option[CantonTimestamp]] = {
    runCmd(
      SequencerAdminCommands.SetTrafficBalance(member, serial, newTotalExtraTrafficLimit)
    )
  }

  def ensureSequencerTrafficControlState(
      member: Member,
      newTotalExtraTrafficLimit: NonNegativeLong,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    retryProvider.ensureThat(
      RetryFor.WaitingOnInitDependency,
      "sequencer_traffic_control",
      s"Extra traffic limit for $member set to $newTotalExtraTrafficLimit",
      getSequencerTrafficControlState(member).map(result =>
        Either.cond(
          result.extraTrafficLimit == newTotalExtraTrafficLimit,
          (),
          result,
        )
      ),
      (previous: TrafficStatus) =>
        setTrafficControlState(
          member,
          newTotalExtraTrafficLimit,
          serial = previous.nextSerial,
        ).map(_ => ()),
      logger,
    )
  }

  def getSequencerPruningStatus()(implicit
      traceContext: TraceContext
  ): Future[SequencerPruningStatus] =
    runCmd(
      SequencerAdminCommands.GetPruningStatus
    )

  def prune(ts: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[String] =
    runCmd(
      EnterpriseSequencerAdminCommands.Prune(ts)
    )

  def disableMember(member: Member)(implicit
      traceContext: TraceContext
  ): Future[Unit] = runCmd(
    EnterpriseSequencerAdminCommands.DisableMember(member)
  )

  override def identity()(implicit traceContext: TraceContext): Future[NodeIdentity] =
    getSequencerId

  override def isNodeInitialized()(implicit traceContext: TraceContext): Future[Boolean] = {
    getStatus.map {
      case NodeStatus.Failure(_) => false
      case NodeStatus.NotInitialized(_) => false
      case NodeStatus.Success(_) => true
    }
  }

  case class TrafficStatus(status: MemberTrafficStatus) extends PrettyPrinting {
    def member: Member = status.member
    def extraTrafficConsumed: NonNegativeLong = status.trafficState.extraTrafficConsumed
    def extraTrafficLimit: NonNegativeLong =
      status.trafficState.extraTrafficLimit.fold(NonNegativeLong.zero)(_.toNonNegative)
    def nextSerial: PositiveInt = status.balanceSerial.fold(PositiveInt.one)(_.increment)

    override def pretty: Pretty[TrafficStatus] = prettyOfClass(
      param("member", _.member),
      param("extraTrafficConsumed", _.extraTrafficConsumed),
      param("extraTrafficLimit", _.extraTrafficLimit),
      param("nextSerial", _.nextSerial),
      param("status", _.status),
    )
  }
}
