package com.daml.network.environment

import cats.syntax.foldable.*
import cats.syntax.traverse.*
import com.daml.lf.archive.DarParser
import com.daml.network.util.UploadablePackage
import com.digitalasset.canton.{DiscardOps, DomainAlias}
import com.digitalasset.canton.admin.api.client.commands.{
  ParticipantAdminCommands,
  VaultAdminCommands,
}
import com.digitalasset.canton.admin.api.client.data.ListConnectedDomainsResult
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.admin.v0.AcsSnapshotChunk
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.traffic.MemberTrafficStatus
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.version.ProtocolVersion
import com.google.protobuf.ByteString
import io.grpc.Status

import java.nio.file.{Files, Path}
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
  )(implicit traceContext: TraceContext): Future[MemberTrafficStatus] = {
    runCmd(
      ParticipantAdminCommands.TrafficControl.GetTrafficControlState(domainId)
    ).map(
      _.getOrElse(throw Status.NOT_FOUND.withDescription("No traffic state").asRuntimeException())
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

  def uploadDarFiles(
      pkgs: Seq[UploadablePackage],
      withLock: (String, () => Future[Unit]) => Future[Unit],
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    // TODO(#5141): allow limit parallel upload once Canton deals with concurrent uploads
    withLock(
      "DAR upload",
      () =>
        pkgs.foldLeft(Future.unit)((previous, dar) =>
          previous.flatMap(_ => uploadDarFile(dar, f => f()))
        ),
    )

  def uploadDarFile(
      pkg: UploadablePackage,
      withLock: (() => Future[Unit]) => Future[Unit],
  )(implicit traceContext: TraceContext): Future[Unit] = {
    withLock(() =>
      uploadDarFileInternal(
        pkg.packageId,
        pkg.resourcePath,
        ByteString.readFrom(pkg.inputStream()),
      )
    )
  }

  def uploadDarFile(
      path: Path,
      withLock: (() => Future[Unit]) => Future[Unit],
  )(implicit traceContext: TraceContext): Future[Unit] = {
    withLock(() =>
      for {
        darFile <- Future {
          ByteString.readFrom(Files.newInputStream(path))
        }
        hash = DarParser.assertReadArchiveFromFile(path.toFile).main.getHash
        _ <- uploadDarFileInternal(hash, path.toString, darFile)
      } yield ()
    )
  }

  private def uploadDarFileInternal(
      packageId: String,
      path: String,
      darFile: => ByteString,
  )(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    retryProvider
      .ensureThatB(
        s"DAR file $path with package-id $packageId has been uploaded.",
        // TODO(#5141) and TODO(#5755): consider if we still need a check here
        packageIsVetted(packageId),
        runCmd(
          ParticipantAdminCommands.Package.UploadDar(Some(path), true, true, logger, Some(darFile))
          // the DAR hashes used by the admin API are different from package IDs, so we ignore them
        ).map(_ => ()),
        logger,
      )

  // TODO(tech-debt) consider removing this clunky code once Canton actually blocks on vetting (Canton issue #11255)
  private def packageIsVetted(
      packageId: String
  )(implicit traceContext: TraceContext): Future[Boolean] = for {
    participantId <- getParticipantId()
    domains <- listConnectedDomains()
    vettedPackagesResults <-
      if (domains.isEmpty) {
        Future.failed(
          Status.FAILED_PRECONDITION
            .withDescription(
              s"We shouldn't check package vetting if we are not connected to any domains."
            )
            .asRuntimeException()
        )
      } else {
        // TODO(tech-debt) we could also replace this with a single call to the API and filter by domains ourselves
        domains.traverse(domain => listVettedPackages(participantId, domain.domainId))
      }
  } yield {
    vettedPackagesResults.forall(
      // there really should be just one result per domain, but let's not make too many assumptions about Canton
      _.exists(vr =>
        vr.mapping.participantId == participantId && vr.mapping.packageIds.contains(packageId)
      )
    )
  }

  def ensureInitialPartyToParticipant(
      partyId: PartyId,
      participantId: ParticipantId,
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[Unit] =
    for {
      _ <- retryProvider.ensureThatB(
        show"Party $partyId is allocated on $participantId",
        listPartyToParticipant(
          TopologyStoreId.AuthorizedStore.filterName,
          filterParty = partyId.filterString,
        ).map(_.nonEmpty),
        proposeInitialPartyToParticipant(partyId, participantId, signedBy),
        logger,
      )
      _ <- retryProvider.waitUntil(
        show"Party allocation of $partyId is visible on all connected domains",
        for {
          domains <- listConnectedDomains()
          _ = if (domains.isEmpty)
            throw Status.FAILED_PRECONDITION
              .withDescription("No domain connected")
              .asRuntimeException()
          else ()
          // TODO(tech-debt) we could also replace this with a single call to the API and filter by domains ourselves
          _ <- domains.traverse_(domain => getPartyToParticipant(domain.domainId, partyId))
        } yield (),
        logger,
      )
    } yield ()

  def listMyKeys()(implicit
      traceContext: TraceContext
  ): Future[Seq[com.digitalasset.canton.crypto.admin.grpc.PrivateKeyMetadata]] = {
    runCmd(VaultAdminCommands.ListMyKeys("", ""))
  }

  def exportKeyPair(fingerprint: Fingerprint)(implicit
      traceContext: TraceContext
  ): Future[ByteString] = {
    runCmd(VaultAdminCommands.ExportKeyPair(fingerprint, ProtocolVersion.latest))
  }
}
