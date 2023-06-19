package com.daml.network.environment

import com.digitalasset.canton.{DomainAlias, DiscardOps}
import com.digitalasset.canton.admin.api.client.commands.ParticipantAdminCommands
import com.digitalasset.canton.admin.api.client.data.ListConnectedDomainsResult
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.admin.v0.AcsSnapshotChunk
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.participant.traffic.TrafficStateController.ParticipantTrafficState
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
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
    loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
    clock: Clock,
)(implicit ec: ExecutionContextExecutor)
    extends TopologyAdminConnection(
      config,
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

  def connectDomain(alias: DomainAlias)(implicit
      traceContext: TraceContext
  ): Future[Boolean] =
    runCmd(ParticipantAdminCommands.DomainConnectivity.ConnectDomain(alias, retry = false))

  def ensureDomainRegistered(
      config: DomainConnectionConfig
  )(implicit traceContext: TraceContext): Future[Unit] = for {
    _ <- retryProvider
      .ensureThat(
        s"participant registered ${config.domain}",
        lookupDomainConnectionConfig(config.domain).map(_.toRight(())),
        (_: Unit) => registerDomain(config),
        logger,
      )
    // Albeit Canton auto-connects on registering a domain that auto-connect fails if the domain is
    // not yet running. So we need to play it safe and ensure connectivity ourselves.
    // This is particularly important, as without that later party-allocations won't get propagated properly.
    // TODO(#5784): see whether we can improve Canton so that this kind of connectivity management is less brittle
    _ <- retryProvider.waitUntil(
      s"participant is connected to ${config.domain}",
      // We're slightly abusing 'waitUntil' here, using a side-effecting condition. It's idempotent though, so all good.
      connectDomain(config.domain).map(isConnected =>
        if (!isConnected) {
          val msg = s"failed to connect to ${config.domain}"
          throw Status.Code.FAILED_PRECONDITION.toStatus.withDescription(msg).asRuntimeException()
        }
      ),
      logger,
    )
  } yield ()

  def getParticipantTrafficState(
      domainId: DomainId
  )(implicit traceContext: TraceContext): Future[ParticipantTrafficState] = {
    runCmd(
      ParticipantAdminCommands.TrafficControl.GetTrafficControlState(domainId)
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

  def getParticipantId()(implicit traceContext: TraceContext): Future[ParticipantId] =
    getId().map(ParticipantId(_))

  def lookupDomainConnectionConfig(
      domain: DomainAlias
  )(implicit traceContext: TraceContext): Future[Option[DomainConnectionConfig]] =
    for {
      configuredDomains <- runCmd(ParticipantAdminCommands.DomainConnectivity.ListConfiguredDomains)
    } yield configuredDomains
      .collectFirst {
        case (config, _) if config.domain == domain => config
      }

  def getDomainConnectionConfig(
      domain: DomainAlias
  )(implicit traceContext: TraceContext): Future[DomainConnectionConfig] =
    lookupDomainConnectionConfig(domain).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(s"Domain $domain is not configured on the participant")
          .asRuntimeException()
      )
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
