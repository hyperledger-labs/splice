// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.implicits.*
import com.digitalasset.canton.admin.api.client.commands.{
  GrpcAdminCommand,
  SequencerAdminCommands,
  TopologyAdminCommands,
}
import com.digitalasset.canton.admin.api.client.data.{NodeStatus, SequencerStatus}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeLong, PositiveInt}
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig, NonNegativeFiniteDuration}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.grpc.ByteStringStreamObserver
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.protocol.StaticSynchronizerParameters
import com.digitalasset.canton.sequencer.admin.v30.OnboardingStateV2Response
import com.digitalasset.canton.sequencing.protocol
import com.digitalasset.canton.synchronizer.sequencer.SequencerPruningStatus
import com.digitalasset.canton.synchronizer.sequencer.admin.grpc.InitializeSequencerResponse
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.admin.grpc.{BaseQuery, TopologyStoreId}
import com.digitalasset.canton.topology.admin.v30.GenesisStateV2Response
import com.digitalasset.canton.topology.store.StoredTopologyTransactions.GenericStoredTopologyTransactions
import com.digitalasset.canton.topology.store.TimeQuery.Snapshot
import com.digitalasset.canton.topology.transaction.{SequencerSynchronizerState, TopologyMapping}
import com.digitalasset.canton.topology.{Member, NodeIdentity, SequencerId}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection.TrafficState
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyResult
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState

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
)(implicit val ec: ExecutionContextExecutor, tracer: Tracer)
    extends TopologyAdminConnection(
      config,
      apiLoggingConfig,
      loggerFactory,
      grpcClientMetrics,
      retryProvider,
    )
    with StatusAdminConnection
    with SequencerBftAdminConnection {

  override val serviceName = "Canton Sequencer Admin API"

  override type Status = SequencerStatus

  override protected def getStatusRequest: GrpcAdminCommand[?, ?, NodeStatus[SequencerStatus]] =
    SequencerAdminCommands.Health.SequencerStatusCommand()

  def getSequencerId(implicit traceContext: TraceContext): Future[SequencerId] =
    getId().map(SequencerId(_))

  def getGenesisState(timestamp: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[Seq[ByteString]] = {
    val responseObserver = new SeqAccumulatingObserver[GenesisStateV2Response]()
    runCmd(
      TopologyAdminCommands.Read
        .GenesisStateV2(
          timestamp = Some(timestamp),
          synchronizerStore = None,
          observer = responseObserver,
        )
    ).flatMap(_ => responseObserver.resultFuture.map(_.map(_.chunk)))
  }

  def getTopologyTransactionsSummary(store: TopologyStoreId, now: CantonTimestamp)(implicit
      traceContext: TraceContext
  ): Future[Map[TopologyMapping.Code, Int]] = {
    runCmd(
      TopologyAdminCommands.Read.ListAll(
        query = BaseQuery(
          store = store,
          proposals = false,
          timeQuery = Snapshot(now),
          ops = None,
          filterSigningKey = "",
          protocolVersion = None,
        ),
        filterNamespace = "",
        excludeMappings = Seq.empty,
      )
    ).map(_.result.groupMapReduce(_.mapping.code)(_ => 1)(_ + _))
  }

  def getOnboardingState(sequencerIdOrTimestamp: Either[SequencerId, CantonTimestamp])(implicit
      traceContext: TraceContext
  ): Future[ByteString] = {
    val responseObserver =
      new ByteStringStreamObserver[OnboardingStateV2Response](_.onboardingStateForSequencer)
    runCmd(
      SequencerAdminCommands.OnboardingStateV2(responseObserver, sequencerIdOrTimestamp)
    ).flatMap(_ => responseObserver.resultBytes)
  }

  /** This is used for initializing the sequencer when the domain is first bootstrapped.
    */
  def initializeFromBeginning(
      topologySnapshot: GenericStoredTopologyTransactions,
      domainParameters: StaticSynchronizerParameters,
  )(implicit traceContext: TraceContext): Future[InitializeSequencerResponse] = {
    val builder = ByteString.newOutput()
    topologySnapshot.result.foreach(_.writeDelimitedTo(domainParameters.protocolVersion, builder))
    runCmd(
      SequencerAdminCommands.InitializeFromGenesisStateV2(
        Seq(builder.toByteString),
        domainParameters,
      )
    )
  }

  /** This is used for initializing the sequencer after hard domain migrations.
    */
  def initializeFromGenesisState(
      genesisState: Seq[ByteString],
      domainParameters: StaticSynchronizerParameters,
  )(implicit traceContext: TraceContext): Future[InitializeSequencerResponse] =
    runCmd(
      SequencerAdminCommands.InitializeFromGenesisStateV2(
        genesisState,
        domainParameters,
      )
    )

  def initializeFromOnboardingState(
      onboardingState: ByteString
  )(implicit traceContext: TraceContext): Future[InitializeSequencerResponse] =
    runCmd(
      SequencerAdminCommands.InitializeFromOnboardingStateV2(
        onboardingState
      )
    )

  def listSequencerTrafficControlState(filterMembers: Seq[Member] = Seq.empty)(implicit
      traceContext: TraceContext
  ): Future[Seq[TrafficState]] =
    runCmd(
      SequencerAdminCommands.GetTrafficControlState(filterMembers)
    ).map(
      _.trafficStates
        .map { case (member, trafficState) =>
          TrafficState(
            member,
            trafficState,
          )
        }
        .toSeq
    )

  def getSequencerTrafficControlState(
      member: Member
  )(implicit traceContext: TraceContext): Future[TrafficState] = {
    lookupSequencerTrafficControlState(member).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(s"No traffic state found for member $member")
          .asRuntimeException()
      )
    )
  }

  def lookupSequencerTrafficControlState(
      member: Member
  )(implicit traceContext: TraceContext): Future[Option[TrafficState]] = {
    listSequencerTrafficControlState(Seq(member)).map {
      case Seq() => None
      case Seq(m) => Some(m)
      case memberList =>
        throw Status.INTERNAL
          .withDescription(
            s"Received more than one traffic status response for member $member: $memberList"
          )
          .asRuntimeException()
    }
  }

  private def setTrafficControlState(
      member: Member,
      newTotalExtraTrafficLimit: NonNegativeLong,
      serial: PositiveInt,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    runCmd(
      SequencerAdminCommands.SetTrafficPurchased(member, serial, newTotalExtraTrafficLimit)
    )
  }

  def getSequencerSynchronizerState()(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[SequencerSynchronizerState]] = {
    for {
      synchronizerId <- getStatus.map(_.trySuccess.synchronizerId)
      sequencerState <- getSequencerSynchronizerState(synchronizerId.logical, AuthorizedState)
    } yield sequencerState
  }

  /** Set the traffic state of currentTrafficState.member to a state with
    *
    * serial >= currentTrafficState.nextSerial and extraTrafficLimit == newTotalExtraTrafficLimit
    * as long as currentSequencerState's serial remains unchanged.
    *
    * Fail with a retryable exception in all other cases, so the caller can recompute the target traffic state
    * and retry setting it.
    */
  def setSequencerTrafficControlState(
      currentTrafficState: TrafficState,
      currentSequencerState: TopologyResult[SequencerSynchronizerState],
      newTotalExtraTrafficLimit: NonNegativeLong,
      clock: Clock,
      timeout: NonNegativeFiniteDuration,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    val msgPrefix =
      s"setting traffic state for ${currentTrafficState.member} to $newTotalExtraTrafficLimit with next serial ${currentTrafficState.nextSerial}:"
    val deadline = clock.now.plus(timeout.asJavaApproximation)
    // There are multiple cases where we need the caller to retry: we (ab)use gRPC Status codes to communicate this.
    def checkSuccessOrAbort(): Future[Option[io.grpc.Status]] = for {
      (sequencerState, trafficState) <- (
        getSequencerSynchronizerState(),
        getSequencerTrafficControlState(currentTrafficState.member),
      ).tupled
    } yield {
      if (
        trafficState.nextSerial == currentTrafficState.nextSerial && sequencerState.base.serial == currentSequencerState.base.serial
      ) {
        val now = clock.now
        if (now.isAfter(deadline)) {
          Some(Status.DEADLINE_EXCEEDED.withDescription(s"$msgPrefix timed out after $timeout"))
        } else {
          None // we did not yet manage to advance the traffic state serial, but there's still time left
        }
      } else if (trafficState.extraTrafficLimit == newTotalExtraTrafficLimit) {
        Some(Status.OK)
      } else if (sequencerState.base.serial != currentSequencerState.base.serial) {
        Some(
          Status.ABORTED.withDescription(
            s"$msgPrefix concurrent change of sequencer state serial to ${sequencerState.base.serial} detected"
          )
        )
      } else {
        if (trafficState.nextSerial < currentTrafficState.nextSerial)
          logger.warn(
            s"$msgPrefix unexpected decrease of traffic state serial from ${currentTrafficState.nextSerial} to ${trafficState.nextSerial}"
          )
        Some(
          Status.ABORTED.withDescription(
            s"$msgPrefix traffic state serial changed to ${trafficState.nextSerial} due a concurrent change of the extraTrafficLimit to ${trafficState.extraTrafficLimit}"
          )
        )
      }
    }

    retryProvider
      .ensureThatO(
        RetryFor.Automation,
        "sequencer_traffic_control",
        s"Extra traffic limit for ${currentTrafficState.member} set to $newTotalExtraTrafficLimit with nextSerial ${currentTrafficState.nextSerial}",
        checkSuccessOrAbort(),
        setTrafficControlState(
          currentTrafficState.member,
          newTotalExtraTrafficLimit,
          serial = currentTrafficState.nextSerial,
        ).map(_ => ()),
        logger,
      )
      .flatMap(status =>
        if (status.isOk) Future.unit else Future.failed(status.asRuntimeException())
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
      SequencerAdminCommands.Prune(ts)
    )

  def disableMember(member: Member)(implicit
      traceContext: TraceContext
  ): Future[Unit] = runCmd(
    SequencerAdminCommands.DisableMember(member)
  )

  override def identity()(implicit traceContext: TraceContext): Future[NodeIdentity] =
    getSequencerId

  override def isNodeInitialized()(implicit traceContext: TraceContext): Future[Boolean] = {
    getStatus.map {
      case NodeStatus.Failure(_) => false
      case NodeStatus.NotInitialized(_, _) => false
      case NodeStatus.Success(_) => true
    }
  }

}

object SequencerAdminConnection {

  case class TrafficState(member: Member, state: protocol.TrafficState) extends PrettyPrinting {
    def extraTrafficConsumed: NonNegativeLong = state.extraTrafficConsumed
    def extraTrafficLimit: NonNegativeLong =
      state.extraTrafficPurchased
    def nextSerial: PositiveInt = state.serial.fold(PositiveInt.one)(_.increment)

    override def pretty: Pretty[TrafficState] = prettyOfClass(
      param("member", _.member),
      param("state", _.state),
    )
  }
}
