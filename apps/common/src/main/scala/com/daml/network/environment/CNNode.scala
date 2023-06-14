package com.daml.network.environment

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.javaapi.data.Identifier
import com.daml.network.config.{CNParticipantClientConfig, SharedCNNodeAppParameters}
import com.daml.network.util.HasHealth
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TracerProvider
import io.grpc.Status

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Subclass of CNNodeBase that provides default initialization for most apps */
abstract class CNNode[State <: AutoCloseable & HasHealth](
    serviceUser: String,
    participantClient: CNParticipantClientConfig,
    parameters: SharedCNNodeAppParameters,
    loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
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
    ) {
  val name: InstanceName

  /** Templates whose packages must be available before init will run.
    * It it save to omit a template if there is another template in the same
    * package in the set.
    */
  protected def requiredTemplates: Set[Identifier] = Set.empty

  protected def ensureUserPrimaryParty(connection: CNLedgerConnection): Future[Unit]

  def initialize(
      ledgerClient: CNLedgerClient,
      party: PartyId,
  ): Future[State]

  override protected def initializeNode(
      ledgerClient: CNLedgerClient
  ): Future[State] = for {
    _ <- Future.successful(())
    _ = logger.info(s"Acquiring ledger connection")
    initConnection = ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)
    _ <- ensureUserPrimaryParty(initConnection)
    serviceParty <-
      retryProvider.getValueWithRetries[PartyId](
        s"primary party of service user $serviceUser",
        initConnection.getPrimaryParty(serviceUser),
        logger,
        // Note: In general, app service users are allocated by the validator app.
        // While the app has a valid access token for its service user but that user has not yet been allocated by the validator app,
        // all ledger API calls with fail with PERMISSION_DENIED.
        // Since this is the first ledger API call in the app, we additionally retry on auth errors here.
        additionalCodes = Seq(Status.Code.PERMISSION_DENIED),
      )
    _ = logger.info(s"Waiting for templates to be uploaded: ${requiredTemplates}")
    _ <- initConnection.waitForPackages(requiredTemplates)
    _ = logger.info(s"Packages available, running app-specific init")
    state <- initialize(ledgerClient, serviceParty)
  } yield state
}
