// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.data.EitherT
import cats.syntax.either.*
import cats.implicits.catsSyntaxParallelTraverse_
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.admin.api.client.commands.{
  GrpcAdminCommand,
  ParticipantAdminCommands,
}
import com.digitalasset.canton.admin.api.client.data.{
  ListConnectedDomainsResult,
  NodeStatus,
  ParticipantStatus,
}
import com.digitalasset.canton.admin.participant.v30.{DarDescription, ExportAcsResponse}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig}
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.SequencerConnectionValidation
import com.digitalasset.canton.sequencing.protocol.TrafficState
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.transaction.{
  HostingParticipant,
  ParticipantPermission,
  PartyToParticipant,
  SignedTopologyTransaction,
  TopologyChangeOp,
  TopologyMapping,
}
import com.digitalasset.canton.topology.{DomainId, NodeIdentity, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.digitalasset.canton.util.ShowUtil.*
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection.{
  HasParticipantId,
  IMPORT_ACS_WORKFLOW_ID_PREFIX,
}
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.{
  RecreateOnAuthorizedStateChange,
  TopologyResult,
  TopologyTransactionType,
}
import org.lfdecentralizedtrust.splice.util.UploadablePackage

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future, Promise}
import scala.reflect.ClassTag

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

  override protected def getStatusRequest: GrpcAdminCommand[_, _, NodeStatus[ParticipantStatus]] =
    ParticipantAdminCommands.Health.ParticipantStatusCommand()

  private val hashOps: HashOps = new HashOps {
    override def defaultHashAlgorithm: com.digitalasset.canton.crypto.HashAlgorithm.Sha256.type =
      HashAlgorithm.Sha256
  }

  private def listConnectedDomains()(implicit
      traceContext: TraceContext
  ): Future[Seq[ListConnectedDomainsResult]] = {
    runCmd(ParticipantAdminCommands.DomainConnectivity.ListConnectedDomains())
  }

  def isNodeInitialized()(implicit traceContext: TraceContext): Future[Boolean] =
    runCmd(getStatusRequest).map {
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
        SequencerConnectionValidation.All,
      )
    )

  def connectDomain(alias: DomainAlias)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    retryProvider.retryForClientCalls(
      "connect_domain",
      s"participant is connected to $alias",
      runCmd(ParticipantAdminCommands.DomainConnectivity.ReconnectDomain(alias, retry = false)).map(
        isConnected =>
          if (!isConnected) {
            val msg = s"failed to connect to ${alias}"
            throw Status.Code.FAILED_PRECONDITION.toStatus.withDescription(msg).asRuntimeException()
          }
      ),
      logger,
    )

  private def disconnectDomain(alias: DomainAlias)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(ParticipantAdminCommands.DomainConnectivity.DisconnectDomain(alias))

  def ensureDomainRegistered(
      config: DomainConnectionConfig,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[Unit] = {
    for {
      _ <- retryProvider
        .ensureThat(
          retryFor,
          "domain_registered_handshake",
          s"participant registered ${config.domain} with handshake only",
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
    _ <- connectDomain(config.domain)
  } yield ()

  private def reconnectDomain(alias: DomainAlias)(implicit
      traceContext: TraceContext
  ): Future[Unit] = for {
    _ <- retryProvider.retryForClientCalls(
      "reconnect_domain_disconnect",
      s"participant is disconnected from $alias",
      disconnectDomain(alias),
      logger,
    )
    _ <- connectDomain(alias)
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
    retryProvider.retryForClientCalls(
      "import_acs",
      "Imports the acs in the participantl",
      runCmd(
        ParticipantAdminCommands.ParticipantRepairManagement
          .ImportAcs(
            acsBytes,
            IMPORT_ACS_WORKFLOW_ID_PREFIX,
            allowContractIdSuffixRecomputation = false,
          )
      ).map(_ => ()),
      logger,
    )
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
      configuredDomains <- runCmd(ParticipantAdminCommands.DomainConnectivity.ListRegisteredDomains)
    } yield configuredDomains
      .collectFirst {
        case (configuredDomain, _) if configuredDomain.domain == domain => configuredDomain
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

  private def setDomainConnectionConfig(config: DomainConnectionConfig)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    runCmd(
      ParticipantAdminCommands.DomainConnectivity.ModifyDomainConnection(
        config,
        SequencerConnectionValidation.All,
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

  private def modifyOrRegisterDomainConnectionConfig(
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

  def ensureInitialPartyToParticipant(
      store: TopologyStoreId,
      partyId: PartyId,
      participantId: ParticipantId,
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
        ).map(_ => ()),
        logger,
      )
    } yield ()

  override def identity()(implicit traceContext: TraceContext): Future[NodeIdentity] =
    getParticipantId()
  def proposeInitialPartyToParticipant(
      store: TopologyStoreId,
      partyId: PartyId,
      participantId: ParticipantId,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransaction[TopologyChangeOp, PartyToParticipant]] = {
    proposeInitialPartyToParticipant(store, partyId, Seq(participantId))
  }
  def proposeInitialPartyToParticipant(
      store: TopologyStoreId,
      partyId: PartyId,
      participants: Seq[ParticipantId],
      isProposal: Boolean = false,
  )(implicit
      traceContext: TraceContext
  ): Future[SignedTopologyTransaction[TopologyChangeOp, PartyToParticipant]] = {
    val hostingParticipants = participants.map(
      HostingParticipant(
        _,
        ParticipantPermission.Submission,
      )
    )
    proposeMapping(
      store,
      PartyToParticipant.tryCreate(
        partyId,
        Thresholds.partyToParticipantThreshold(hostingParticipants),
        hostingParticipants,
      ),
      serial = PositiveInt.one,
      isProposal = isProposal,
    )
  }

  def ensurePartyToParticipantRemovalProposal(
      domainId: DomainId,
      party: PartyId,
      participantToRemove: ParticipantId,
  )(implicit
      traceContext: TraceContext
  ): Future[TopologyResult[PartyToParticipant]] = {
    def removeParticipant(participants: Seq[HostingParticipant]): Seq[HostingParticipant] = {
      participants.filterNot(_.participantId == participantToRemove)
    }
    ensurePartyToParticipantProposal(
      s"Party $party is proposed to be removed from $participantToRemove",
      domainId,
      party,
      removeParticipant,
    )
  }

  def ensurePartyToParticipantAdditionProposal(
      domainId: DomainId,
      party: PartyId,
      newParticipant: ParticipantId,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] = {
    def addParticipant(participants: Seq[HostingParticipant]): Seq[HostingParticipant] = {
      // New participants are only given Observation rights. We explicitly promote them to Submission rights later.
      // See SvOnboardingPromoteToSubmitterTrigger.
      val newHostingParticipant =
        HostingParticipant(newParticipant, ParticipantPermission.Observation)
      if (participants.map(_.participantId).contains(newHostingParticipant.participantId)) {
        participants
      } else {
        participants.appended(newHostingParticipant)
      }
    }
    ensurePartyToParticipantProposal(
      s"Party $party is proposed to be added on $newParticipant",
      domainId,
      party,
      addParticipant,
    )
  }

  def ensurePartyToParticipantAdditionProposalWithSerial(
      domainId: DomainId,
      party: PartyId,
      newParticipant: ParticipantId,
      expectedSerial: PositiveInt,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] = {
    ensureTopologyMapping[PartyToParticipant](
      TopologyStoreId.DomainStore(domainId),
      show"Party $party is authorized on $newParticipant",
      EitherT(
        getPartyToParticipant(domainId, party)
          .map(result =>
            Either
              .cond(
                result.mapping.participants
                  .exists(hosting => hosting.participantId == newParticipant),
                result,
                result,
              )
          )
      ),
      previous => {
        val newHostingParticipants = previous.participants.appended(
          HostingParticipant(
            newParticipant,
            ParticipantPermission.Observation,
          )
        )
        Right(
          PartyToParticipant.tryCreate(
            previous.partyId,
            participants = newHostingParticipants,
            threshold = Thresholds
              .partyToParticipantThreshold(newHostingParticipants),
          )
        )
      },
      RetryFor.ClientCalls,
      isProposal = true,
      recreateOnAuthorizedStateChange = RecreateOnAuthorizedStateChange.Abort(expectedSerial),
    )
  }

  // the participantChange participant sequence must be ordering, if not canton will consider topology proposals with different ordering as fully different proposals and will not aggregate signatures
  private def ensurePartyToParticipantProposal(
      description: String,
      domainId: DomainId,
      party: PartyId,
      participantChange: Seq[HostingParticipant] => Seq[
        HostingParticipant
      ], // participantChange must be idempotent
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] = {
    def findPartyToParticipant(topologyTransactionType: TopologyTransactionType) = EitherT {
      topologyTransactionType match {
        case transactionType @ (TopologyTransactionType.ProposalSignedByOwnKey |
            TopologyTransactionType.AllProposals) =>
          listPartyToParticipant(
            filterStore = domainId.filterString,
            filterParty = party.filterString,
            proposals = transactionType,
          ).flatMap { proposals =>
            val proposalsWithRightSignature = transactionType match {
              case TopologyTransactionType.ProposalSignedByOwnKey =>
                for {
                  participantId <- getParticipantId()
                  delegations <- listNamespaceDelegation(participantId.namespace, None).map(
                    _.map(_.mapping.target.fingerprint)
                  )
                } yield {
                  val validSigningKeys = delegations :+ participantId.fingerprint
                  proposals.filter { proposal =>
                    proposal.base.signedBy
                      .intersect(validSigningKeys)
                      .nonEmpty
                  }
                }
              case _ => Future.successful(proposals)
            }
            proposalsWithRightSignature.map {
              _.find(proposal => {
                val newHostingParticipants = participantChange(
                  proposal.mapping.participants
                )
                proposal.mapping.participantIds ==
                  newHostingParticipants.map(
                    _.participantId
                  ) && proposal.mapping.threshold == Thresholds.partyToParticipantThreshold(
                    newHostingParticipants
                  )
              })
                .getOrElse(
                  throw Status.NOT_FOUND
                    .withDescription(
                      s"No party to participant proposal for party $party on domain $domainId"
                    )
                    .asRuntimeException()
                )
                .asRight
            }
          }
        case TopologyTransactionType.AuthorizedState =>
          getPartyToParticipant(domainId, party).map(result => {
            val newHostingParticipants = participantChange(
              result.mapping.participants
            )
            Either.cond(
              result.mapping.participantIds ==
                newHostingParticipants.map(_.participantId),
              result,
              result,
            )
          })
      }
    }

    ensureTopologyProposal[PartyToParticipant](
      TopologyStoreId.DomainStore(domainId),
      description,
      queryType => findPartyToParticipant(queryType),
      previous => {
        val newHostingParticipants = participantChange(previous.participants)
        Right(
          PartyToParticipant.tryCreate(
            previous.partyId,
            participants = newHostingParticipants,
            threshold = Thresholds.partyToParticipantThreshold(newHostingParticipants),
          )
        )
      },
      RetryFor.WaitingOnInitDependency,
    )
  }

  def ensureHostingParticipantIsPromotedToSubmitter(
      domainId: DomainId,
      party: PartyId,
      participantId: ParticipantId,
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[TopologyResult[PartyToParticipant]] = {
    def promoteParticipantToSubmitter(
        participants: Seq[HostingParticipant]
    ): Seq[HostingParticipant] = {
      val newValue = HostingParticipant(participantId, ParticipantPermission.Submission)
      val oldIndex = participants.indexWhere(_.participantId == newValue.participantId)
      participants.updated(oldIndex, newValue)
    }

    ensureTopologyMapping[PartyToParticipant](
      TopologyStoreId.DomainStore(domainId),
      s"Participant $participantId is promoted to have Submission permission for party $party",
      EitherT(getPartyToParticipant(domainId, party).map(result => {
        Either.cond(
          result.mapping.participants
            .contains(HostingParticipant(participantId, ParticipantPermission.Submission)),
          result,
          result,
        )
      })),
      previous => {
        Either.cond(
          previous.participants.exists(_.participantId == participantId), {
            val newHostingParticipants = promoteParticipantToSubmitter(previous.participants)
            PartyToParticipant.tryCreate(
              previous.partyId,
              participants = newHostingParticipants,
              threshold = Thresholds.partyToParticipantThreshold(newHostingParticipants),
            )
          },
          show"Participant $participantId does not host party $party",
        )
      },
      retryFor,
      isProposal = true,
    )
  }

  /** Version of [[ensureTopologyMapping]] that also handles proposals:
    * - a new topology transaction is created as a proposal
    * - checks the proposals as well to see if the check holds
    */
  private def ensureTopologyProposal[M <: TopologyMapping: ClassTag](
      store: TopologyStoreId,
      description: String,
      check: TopologyTransactionType => EitherT[Future, TopologyResult[M], TopologyResult[M]],
      update: M => Either[String, M],
      retryFor: RetryFor,
  )(implicit traceContext: TraceContext): Future[TopologyResult[M]] = {
    ensureTopologyMapping(
      store,
      s"proposal $description",
      check(TopologyTransactionType.AuthorizedState)
        .leftFlatMap { authorizedState =>
          EitherT(
            check(TopologyTransactionType.ProposalSignedByOwnKey)
              .leftMap(_ => authorizedState)
              .value
              .recover {
                case ex: StatusRuntimeException if ex.getStatus.getCode == Status.Code.NOT_FOUND =>
                  Left(authorizedState)
              }
          )
        },
      update,
      retryFor,
      isProposal = true,
    )
  }

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
    override def timeoutType
        : com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand.DefaultUnboundedTimeout.type =
      GrpcAdminCommand.DefaultUnboundedTimeout

  }

  /** Like [[ParticipantAdminConnection]], but document that the scope is only
    * interested in the `getParticipantId` feature.
    */
  sealed trait HasParticipantId {
    def getParticipantId()(implicit traceContext: TraceContext): Future[ParticipantId]
  }

  object HasParticipantId {
    @com.google.common.annotations.VisibleForTesting
    private[splice] def Const(participantId: ParticipantId): HasParticipantId =
      new HasParticipantId {
        override def getParticipantId()(implicit traceContext: TraceContext) =
          Future successful participantId
      }

    /** For tests that don't care about the random separation provided by the
      * participant ID in the hash.
      */
    @com.google.common.annotations.VisibleForTesting
    private[splice] val ForTesting = Const(ParticipantId("OnlyForTesting"))
  }
}
