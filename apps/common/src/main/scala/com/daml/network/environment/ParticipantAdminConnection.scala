package com.daml.network.environment

import com.digitalasset.canton.{DomainAlias, DiscardOps}
import com.digitalasset.canton.admin.api.client.commands.ParticipantAdminCommands
import com.digitalasset.canton.admin.api.client.data.ListConnectedDomainsResult
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.admin.v0.AcsSnapshotChunk
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
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
    retryProvider: RetryProvider,
    clock: Clock,
)(implicit ec: ExecutionContextExecutor)
    extends TopologyAdminConnection(
      config,
      timeouts,
      loggerFactory,
      retryProvider,
      clock,
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

  def registerDomain(config: DomainConnectionConfig)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(ParticipantAdminCommands.DomainConnectivity.RegisterDomain(config))

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
    getId(useXNodes).map(ParticipantId(_))

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
      f: DomainConnectionConfig => Option[DomainConnectionConfig],
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      oldConfig <- getDomainConnectionConfig(domain)
      newConfig = f(oldConfig)
      _ <- newConfig match {
        case None =>
          logger.info("No update to domain connection config required")
          Future.unit
        case Some(config) =>
          logger.info("Updating to new domain connection config")
          setDomainConnectionConfig(config)
      }
    } yield ()
}
