// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.environment

import cats.implicits.catsSyntaxParallelTraverse_
import com.daml.network.admin.api.client.GrpcClientMetrics
import com.daml.network.environment.ParticipantAdminConnection.{
  HasParticipantId,
  IMPORT_ACS_WORKFLOW_ID_PREFIX,
}
import com.daml.network.util.UploadablePackage
import com.digitalasset.canton.admin.api.client.commands.{
  ParticipantAdminCommands,
  StatusAdminCommands,
}
import com.digitalasset.canton.admin.api.client.data.ListConnectedDomainsResult
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig}
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.health.admin.data.{NodeStatus, ParticipantStatus}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.SequencerConnectionValidation
import com.digitalasset.canton.topology.{DomainId, NodeIdentity, ParticipantId, PartyId}
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.admin.participant.v30.{DarDescription, ExportAcsResponse}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.sequencing.protocol.TrafficState
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.google.protobuf.ByteString
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}

/** Connection to the subset of the Canton admin API that we rely
  * on in our own applications.
  */
class ParticipantAdminConnection(
    config: ClientConfig,
    apiLoggingConfig: ApiLoggingConfig,
    loggerFactory: NamedLoggerFactory,
    grpcClientMetrics: GrpcClientMetrics,
    retryProvider: RetryProvider,
)(implicit ec: ExecutionContextExecutor, tracer: Tracer)
    extends TopologyAdminConnection(
      config,
      apiLoggingConfig,
      loggerFactory,
      grpcClientMetrics,
      retryProvider,
    )
    with HasParticipantId
    with StatusAdminConnection {
  override val serviceName = "Canton Participant Admin API"

  override protected type Status = ParticipantStatus

  override protected def getStatusRequest: StatusAdminCommands.GetStatus[ParticipantStatus] =
    new StatusAdminCommands.GetStatus(ParticipantStatus.fromProtoV30)

  private val hashOps = new HashOps {
    override def defaultHashAlgorithm = HashAlgorithm.Sha256
  }

  private def listConnectedDomains()(implicit
      traceContext: TraceContext
  ): Future[Seq[ListConnectedDomainsResult]] = {
    runCmd(ParticipantAdminCommands.DomainConnectivity.ListConnectedDomains())
  }

  private val participantStatusCommand =
    new StatusAdminCommands.GetStatus(ParticipantStatus.fromProtoV30)

  def isNodeInitialized()(implicit traceContext: TraceContext): Future[Boolean] =
    runCmd(participantStatusCommand).map {
      case NodeStatus.Failure(_) => false
      case NodeStatus.NotInitialized(_, _) => false
      case NodeStatus.Success(_) => true
    }

  def getDomainId(domainAlias: DomainAlias)(implicit
      traceContext: TraceContext
  ): Future[DomainId] =
    // We avoid ParticipantAdminCommands.DomainConnectivity.GetDomainId which tries to make
    // a new request to the sequencer to query the domain id. ListConnectedDomains
    // on the other hand relies on a cache
    listConnectedDomains().map(
      _.find(
        _.domainAlias == domainAlias
      ).fold(
        throw Status.NOT_FOUND
          .withDescription(s"Domain with alias $domainAlias is not connected")
          .asRuntimeException()
      )(_.domainId)
    )

  /** Usually you want getDomainId instead which is much faster if the domain is connected
    *  but in some cases we want to check the domain id
    * without risking a full domain connection.
    */
  def getDomainIdWithoutConnecting(domainAlias: DomainAlias)(implicit
      traceContext: TraceContext
  ): Future[DomainId] =
    runCmd(
      ParticipantAdminCommands.DomainConnectivity.GetDomainId(domainAlias)
    )

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

  private def registerDomain(config: DomainConnectionConfig, handshakeOnly: Boolean)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(
      ParticipantAdminCommands.DomainConnectivity.RegisterDomain(
        config,
        handshakeOnly,
        // TODO(#10985) Consider enabling this
        SequencerConnectionValidation.Disabled,
      )
    )

  def connectDomain(alias: DomainAlias)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(ParticipantAdminCommands.DomainConnectivity.ConnectDomain(alias, retry = false)).map(
      isConnected =>
        if (!isConnected) {
          val msg = s"failed to connect to ${alias}"
          throw Status.Code.FAILED_PRECONDITION.toStatus.withDescription(msg).asRuntimeException()
        }
    )

  def disconnectDomain(alias: DomainAlias)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(ParticipantAdminCommands.DomainConnectivity.DisconnectDomain(alias))

  def ensureDomainRegistered(
      config: DomainConnectionConfig,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    require(
      !config.manualConnect,
      "manualConnect must be false when trying to register only, otherwise it doesn't even handshake",
    )
    for {
      _ <- retryProvider
        .ensureThat(
          retryFor,
          "domain_registered_handshake",
          s"participant registered ${config.domain}",
          lookupDomainConnectionConfig(config.domain).map(_.toRight(())),
          (_: Unit) => registerDomain(config, handshakeOnly = true),
          logger,
        )
    } yield ()
  }

  def ensureDomainRegisteredNoHandshake(
      config: DomainConnectionConfig,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    require(
      config.manualConnect,
      "manualConnect must be true when trying to register only",
    )
    for {
      _ <- retryProvider
        .ensureThat(
          retryFor,
          "domain_registered_no_handshake",
          s"participant registered ${config.domain}",
          lookupDomainConnectionConfig(config.domain).map(_.toRight(())),
          (_: Unit) => registerDomain(config, handshakeOnly = false),
          logger,
        )
    } yield ()
  }

  def ensureDomainRegisteredAndConnected(
      config: DomainConnectionConfig,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] = for {
    _ <- retryProvider
      .ensureThat(
        retryFor,
        "domain_registered",
        s"participant registered ${config.domain} with config $config",
        lookupDomainConnectionConfig(config.domain).map {
          case Some(existingConfig) if existingConfig == config => Right(())
          case Some(other) => Left(Some(other))
          case None => Left(None)
        },
        (existingDomainConfig: Option[DomainConnectionConfig]) =>
          existingDomainConfig match {
            case None =>
              logger.info(s"Registering new domain with config $config")
              registerDomain(config, handshakeOnly = false)
            case Some(_) =>
              modifyDomainConnectionConfigAndReconnect(config.domain, _ => Some(config)).map(_ =>
                ()
              )
          },
        logger,
      )
    // Albeit Canton auto-connects on registering a domain that auto-connect fails if the domain is
    // not yet running. So we need to play it safe and ensure connectivity ourselves.
    // This is particularly important, as without that later party-allocations won't get propagated properly.
    // TODO(#5784): see whether we can improve Canton so that this kind of connectivity management is less brittle
    _ <- retryProvider.waitUntil(
      retryFor,
      "domain_connected",
      s"participant is connected to ${config.domain}",
      // We're slightly abusing 'waitUntil' here, using a side-effecting condition. It's idempotent though, so all good.
      connectDomain(config.domain),
      logger,
    )
  } yield ()

  def reconnectDomain(alias: DomainAlias)(implicit
      traceContext: TraceContext
  ): Future[Unit] = for {
    _ <- disconnectDomain(alias)
    _ <- retryProvider.retryForClientCalls(
      "reconnect_domain",
      s"participant is connected to $alias",
      connectDomain(alias),
      logger,
    )
  } yield ()

  def getParticipantTrafficState(
      domainId: DomainId
  )(implicit traceContext: TraceContext): Future[TrafficState] = {
    runCmd(
      ParticipantAdminCommands.TrafficControl.GetTrafficControlState(domainId)
    )
  }

  def downloadAcsSnapshot(
      parties: Set[PartyId],
      filterDomainId: Option[DomainId] = None,
      timestamp: Option[Instant] = None,
      force: Boolean = false,
  )(implicit traceContext: TraceContext): Future[ByteString] = {
    logger.debug(
      show"Downloading ACS snapshot from domain $filterDomainId, for parties $parties at timestamp $timestamp"
    )
    val requestComplete = Promise[ByteString]()
    // TODO(#3298) just concatenate the byteString here. Make it scale to 2M contracts.
    val observer = new GrpcByteChunksToByteArrayObserver[ExportAcsResponse](requestComplete)
    runCmd(
      ParticipantAdminCommands.ParticipantRepairManagement.ExportAcs(
        parties = parties,
        partiesOffboarding = false,
        filterDomainId,
        timestamp,
        observer,
        Map.empty,
        force,
      )
    ).discard
    requestComplete.future
  }

  def uploadAcsSnapshot(acsBytes: ByteString)(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    runCmd(
      ParticipantAdminCommands.ParticipantRepairManagement
        .ImportAcs(
          acsBytes,
          IMPORT_ACS_WORKFLOW_ID_PREFIX,
          allowContractIdSuffixRecomputation = false,
        )
    ).map(_ => ())
  }

  def getParticipantId()(implicit traceContext: TraceContext): Future[ParticipantId] =
    getId().map(ParticipantId(_))

  def listConnectedDomain()(implicit
      traceContext: TraceContext
  ): Future[Seq[ListConnectedDomainsResult]] =
    for {
      connectedDomain <- runCmd(ParticipantAdminCommands.DomainConnectivity.ListConnectedDomains())
    } yield connectedDomain

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
      ParticipantAdminCommands.DomainConnectivity.ModifyDomainConnection(
        config,
        // TODO(#10985) Consider enabling this
        SequencerConnectionValidation.Disabled,
      )
    )

  def modifyDomainConnectionConfig(
      domain: DomainAlias,
      f: DomainConnectionConfig => Option[DomainConnectionConfig],
  )(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      oldConfig <- getDomainConnectionConfig(domain)
      newConfig = f(oldConfig)
      configModified <- newConfig match {
        case None =>
          logger.trace("No update to domain connection config required")
          Future.successful(false)
        case Some(config) =>
          logger.info(
            s"Updating to new domain connection config for domain $domain. Old config: $oldConfig, new config: $config"
          )
          for {
            _ <- setDomainConnectionConfig(config)
          } yield true
      }
    } yield configModified

  def modifyOrRegisterDomainConnectionConfig(
      config: DomainConnectionConfig,
      f: DomainConnectionConfig => Option[DomainConnectionConfig],
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      configO <- lookupDomainConnectionConfig(config.domain)
      needsReconnect <- configO match {
        case Some(config) =>
          modifyDomainConnectionConfig(
            config.domain,
            f,
          )
        case None =>
          logger.info(s"Domain ${config.domain} is new, registering")
          ensureDomainRegisteredAndConnected(
            config,
            retryFor,
          ).map(_ => false)
      }
    } yield needsReconnect

  def modifyDomainConnectionConfigAndReconnect(
      domain: DomainAlias,
      f: DomainConnectionConfig => Option[DomainConnectionConfig],
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      configModified <- modifyDomainConnectionConfig(domain, f)
      _ <-
        if (configModified) {
          logger.info(
            s"reconnect to the domain $domain for new sequencer configuration to take effect"
          )
          reconnectDomain(domain)
        } else Future.unit
    } yield ()

  def modifyOrRegisterDomainConnectionConfigAndReconnect(
      config: DomainConnectionConfig,
      f: DomainConnectionConfig => Option[DomainConnectionConfig],
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      configModified <- modifyOrRegisterDomainConnectionConfig(config, f, retryFor)
      _ <-
        if (configModified) {
          logger.info(
            s"reconnect to the domain ${config.domain} for new sequencer configuration to take effect"
          )
          reconnectDomain(config.domain)
        } else Future.unit
    } yield ()

  def uploadDarFiles(
      pkgs: Seq[UploadablePackage],
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    pkgs.parTraverse_(
      uploadDarFile(_, retryFor)
    )

  def uploadDarFileLocally(
      pkg: UploadablePackage,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] =
    uploadDarLocally(
      pkg.resourcePath,
      ByteString.readFrom(pkg.inputStream()),
      retryFor,
    )
  def uploadDarFile(
      pkg: UploadablePackage,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] =
    uploadDarFileInternal(
      pkg.resourcePath,
      ByteString.readFrom(pkg.inputStream()),
      retryFor,
    )

  def uploadDarFile(
      path: Path,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      darFile <- Future {
        ByteString.readFrom(Files.newInputStream(path))
      }
      _ <- uploadDarFileInternal(path.toString, darFile, retryFor)
    } yield ()

  def lookupDar(hash: Hash)(implicit traceContext: TraceContext): Future[Option[ByteString]] =
    runCmd(
      ParticipantAdminConnection.LookupDarByteString(hash)
    )

  def listDars(limit: PositiveInt = PositiveInt.MaxValue)(implicit
      traceContext: TraceContext
  ): Future[Seq[DarDescription]] =
    runCmd(
      ParticipantAdminCommands.Package.ListDars(limit)
    )
  private def uploadDarLocally(
      path: String,
      darFile: => ByteString,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    val darHash = hashOps.digest(HashPurpose.DarIdentifier, darFile)
    for {
      _ <- retryProvider
        .ensureThatO(
          retryFor,
          "upload_dar_locally",
          s"DAR file $path with hash $darHash has been uploaded.",
          lookupDar(darHash).map(_.map(_ => ())),
          runCmd(
            ParticipantAdminCommands.Package
              .UploadDar(
                Some(path),
                vetAllPackages = true,
                synchronizeVetting = false,
                logger,
                Some(darFile),
              )
          ).map(_ => ()),
          logger,
        )
    } yield ()
  }

  private def uploadDarFileInternal(
      path: String,
      darFile: => ByteString,
      retryFor: RetryFor,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    val darHash = hashOps.digest(HashPurpose.DarIdentifier, darFile)
    for {
      _ <- retryProvider
        .ensureThatO(
          retryFor,
          "upload_dar",
          s"DAR file $path with hash $darHash has been uploaded.",
          // TODO(#5141) and TODO(#5755): consider if we still need a check here
          lookupDar(darHash).map(_.map(_ => ())),
          runCmd(
            ParticipantAdminCommands.Package
              .UploadDar(
                Some(path),
                vetAllPackages = true,
                synchronizeVetting = true,
                logger,
                Some(darFile),
              )
          ).map(_ => ()),
          logger,
        )
    } yield ()
  }

  def unVetDar(darHash: String)(implicit traceContext: TraceContext): Future[Unit] = {
    runCmd(ParticipantAdminCommands.Package.UnvetDar(darHash))
  }

  def vetDar(darHash: String)(implicit traceContext: TraceContext): Future[Unit] = {
    runCmd(ParticipantAdminCommands.Package.VetDar(darHash, false))
  }

  def ensureInitialPartyToParticipant(
      store: TopologyStoreId,
      partyId: PartyId,
      participantId: ParticipantId,
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      _ <- retryProvider.ensureThatB(
        RetryFor.WaitingOnInitDependency,
        "initial_party_to_participant",
        show"Party $partyId is allocated on $participantId",
        listPartyToParticipant(
          store.filterName,
          filterParty = partyId.filterString,
        ).map(_.nonEmpty),
        proposeInitialPartyToParticipant(
          store,
          partyId,
          participantId,
          signedBy,
        ).map(_ => ()),
        logger,
      )
    } yield ()

  override def identity()(implicit traceContext: TraceContext): Future[NodeIdentity] =
    getParticipantId()
}

object ParticipantAdminConnection {
  import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
  import com.digitalasset.canton.admin.participant.v30.*
  import com.digitalasset.canton.admin.participant.v30.PackageServiceGrpc.PackageServiceStub
  import io.grpc.ManagedChannel

  final val IMPORT_ACS_WORKFLOW_ID_PREFIX = "canton-network-acs-import"

  // The Canton APIs insist on writing the bytestring to a file so we define
  // our own variant.
  final case class LookupDarByteString(
      darHash: Hash
  ) extends GrpcAdminCommand[GetDarRequest, GetDarResponse, Option[ByteString]] {
    override type Svc = PackageServiceStub

    override def createService(channel: ManagedChannel): PackageServiceStub =
      PackageServiceGrpc.stub(channel)

    override def createRequest(): Either[String, GetDarRequest] =
      Right(GetDarRequest(darHash.toHexString))

    override def submitRequest(
        service: PackageServiceStub,
        request: GetDarRequest,
    ): Future[GetDarResponse] =
      service.getDar(request)

    override def handleResponse(response: GetDarResponse): Either[String, Option[ByteString]] =
      // For some reason the API does not throw a NOT_FOUND but instead returns
      // a successful response with data set to an empty bytestring.
      // To make things extra fun, this is inconsistent. Other APIs on the package service
      // do return NOT_FOUND.
      Right(Option.when(!response.data.isEmpty)(response.data))

    // might be a big file to download
    override def timeoutType = GrpcAdminCommand.DefaultUnboundedTimeout

  }

  /** Like [[ParticipantAdminConnection]], but document that the scope is only
    * interested in the `getParticipantId` feature.
    */
  sealed trait HasParticipantId {
    def getParticipantId()(implicit traceContext: TraceContext): Future[ParticipantId]
  }

  object HasParticipantId {
    @com.google.common.annotations.VisibleForTesting
    private[network] def Const(participantId: ParticipantId): HasParticipantId =
      new HasParticipantId {
        override def getParticipantId()(implicit traceContext: TraceContext) =
          Future successful participantId
      }

    /** For tests that don't care about the random separation provided by the
      * participant ID in the hash.
      */
    @com.google.common.annotations.VisibleForTesting
    private[network] val ForTesting = Const(ParticipantId("OnlyForTesting"))
  }
}
