package com.daml.network.environment

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.CNNodeMetrics
import com.daml.network.config.{CNParticipantClientConfig, SharedCNNodeAppParameters}
import com.daml.network.util.HasHealth
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TracerProvider
import io.grpc.Status

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Subclass of CNNodeBase that provides default initialization for most apps */
abstract class CNNode[State <: AutoCloseable & HasHealth](
    serviceUser: String,
    participantClient: CNParticipantClientConfig,
    parameters: SharedCNNodeAppParameters,
    loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
    nodeMetrics: CNNodeMetrics,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
) extends CNNodeBase[State](
      serviceUser,
      participantClient,
      parameters,
      loggerFactory,
      tracerProvider,
      futureSupervisor,
      nodeMetrics,
    ) {
  val name: InstanceName

  /** Packages that must be available before init will run.
    */
  protected def requiredPackageIds: Set[String] = Set.empty

  // Code that is run after a ledger connection becomes available but before
  // waiting for the primary party. This can be used for things like
  // domain connections and allocation of the primary party.
  @nowarn("cat=unused")
  protected def preInitializeAfterLedgerConnection(
      connection: CNLedgerConnection,
      ledgerClient: CNLedgerClient,
  ): Future[Unit] =
    Future.unit

  def initialize(
      ledgerClient: CNLedgerClient,
      party: PartyId,
  ): Future[State]

  override protected def initializeNode(
      ledgerClient: CNLedgerClient
  ): Future[State] = for {
    _ <- preInitializeBeforeLedgerConnection()
    _ = logger.info(s"Acquiring ledger connection")
    initConnection = ledgerClient.connection(
      this.getClass.getSimpleName,
      loggerFactory,
      PackageIdResolver.NO_COMMAND_SUBMISSION,
    )
    _ <- preInitializeAfterLedgerConnection(initConnection, ledgerClient)
    serviceParty <-
      retryProvider.getValueWithRetries[PartyId](
        RetryFor.WaitingOnInitDependency,
        s"primary party of service user $serviceUser",
        initConnection.getPrimaryParty(serviceUser),
        logger,
        // Note: In general, app service users are allocated by the validator app.
        // While the app has a valid access token for its service user but that user has not yet been allocated by the validator app,
        // all ledger API calls with fail with PERMISSION_DENIED.
        // Since this is the first ledger API call in the app, we additionally retry on auth errors here.
        additionalCodes = Seq(Status.Code.PERMISSION_DENIED),
      )
    _ = logger.info(s"Waiting for packages to be uploaded: ${requiredPackageIds}")
    _ <- initConnection.waitForPackages(requiredPackageIds)
    _ = logger.info(s"Packages available, running app-specific init")
    state <- initialize(ledgerClient, serviceParty)
  } yield state
}
