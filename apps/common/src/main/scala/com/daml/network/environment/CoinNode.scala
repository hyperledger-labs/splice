package com.daml.network.environment

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.javaapi.data.Identifier
import com.daml.network.auth.AuthTokenSource
import com.daml.network.config.{CoinRemoteParticipantConfig, SharedCoinAppParameters}
import com.daml.network.util.HasHealth
import com.digitalasset.canton.config.RequireTypes.{InstanceName, Port}
import com.digitalasset.canton.environment.CantonNode
import com.digitalasset.canton.health.admin.data.{NodeStatus, SimpleStatus, TopologyQueueStatus}
import com.digitalasset.canton.lifecycle.{AsyncCloseable, AsyncOrSyncCloseable, FlagCloseableAsync}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.HasUptime
import com.digitalasset.canton.topology.{PartyId, UniqueIdentifier}
import com.digitalasset.canton.tracing.{NoTracing, TracerProvider}
import io.grpc.{Status, StatusRuntimeException}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}

/** A running instance of a canton node */
abstract class CoinNode[State <: AutoCloseable & HasHealth](
    serviceUser: String,
    remoteParticipant: CoinRemoteParticipantConfig,
    parameters: SharedCoinAppParameters,
    loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    retryProvider: CoinRetries,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
) extends CantonNode
    with FlagCloseableAsync
    with NamedLogging
    with HasUptime
    with NoTracing {
  val name: InstanceName

  override val timeouts = parameters.processingTimeouts

  private val isInitializedVar: AtomicReference[Boolean] = new AtomicReference(false)

  protected def isInitialized = isInitializedVar.get()

  protected def isActive: Boolean = {
    // initialized and the state reports itself as healthy
    isInitialized && initializeF.value.exists(_.toOption.exists(_.isHealthy))
  }

  protected def ports: Map[String, Port]

  /** Templates whose packages must be available before init will run.
    * It it save to omit a template if there is another template in the same
    * package in the set.
    */
  protected def requiredTemplates: Set[Identifier] = Set.empty

  // TODO(#736): fork or generalize status definition.
  override def status: Future[NodeStatus.Status] = {
    val status = SimpleStatus(
      uid = UniqueIdentifier.tryFromProtoPrimitive(s"coin::$name"),
      uptime = uptime(),
      ports = ports,
      active = isActive,
      topologyQueue = TopologyQueueStatus(0, 0, 0),
    )
    Future.successful(status)
  }

  def initialize(
      ledgerClient: CoinLedgerClient,
      party: PartyId,
  ): Future[State]

  private def waitForPackages(connection: CoinLedgerConnection): Future[Unit] = {
    val requiredPackageIds: Set[String] = requiredTemplates.map(t => t.getPackageId)

    def query(): Future[Unit] = for {
      packages <- (connection.listPackages(): Future[Set[String]])
    } yield {
      val actual = packages
      val missing = requiredPackageIds -- actual
      if (missing.isEmpty) {
        ()
      } else {
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription(s"Missing packages $missing, got $actual")
        )
      }
    }

    retryProvider.retryForAutomation("Wait for packages", query(), this)

  }

  private def createLedgerClient(): Future[CoinLedgerClient] = for {
    _ <- Future.successful(())
    _ = logger.info("Creating ledger API auth token source")
    authTokenSource = AuthTokenSource.fromConfig(
      remoteParticipant.ledgerApi.authConfig,
      loggerFactory,
    )
    token <- retryProvider.retryForAutomation(
      "Acquiring initial auth token",
      authTokenSource.getToken,
      this,
    )
    _ = logger.debug(s"Using token $token for this ledger client")
    client <- retryProvider.retryForAutomation(
      "Acquiring coin ledger client",
      CoinLedgerClient.create(
        remoteParticipant.ledgerApi.clientConfig,
        // Note: When ledger API auth is enabled, application ID must be equal to user ID
        serviceUser,
        // TODO(#1596): Make sure the client correctly refreshes tokens.
        //  E.g., by adding something like [[com.digitalasset.canton.sequencing.authentication.grpc.AuthenticationTokenManager]] to
        //  automatically re-fetch tokens shortly before they expire and then a [[io.grpc.ClientInterceptor]] to add the current token
        //  to all requests.
        token,
        timeouts,
        parameters.loggingConfig.api,
        loggerFactory,
        tracerProvider,
      ),
      this,
    )
  } yield client

  private def initializeNode(ledgerClient: CoinLedgerClient) = for {
    _ <- Future.successful(())
    _ = logger.info(s"Acquiring ledger connection")
    connection = ledgerClient.connection(name.toString)
    _ = logger.info(s"Acquiring primary party of service user $serviceUser")
    serviceParty <-
      retryProvider.retryForAutomation(
        "Querying primary party of user",
        connection.getPrimaryParty(serviceUser),
        this,
        // Note: In general, app service users are allocated by the validator app.
        // While the app has a valid access token for its service user but that user has not yet been allocated by the validator app,
        // all ledger API calls with fail with PERMISSION_DENIED.
        // Since this is the first ledger API call in the app, we additionally retry on auth errors here.
        additionalCodes = Seq(Status.Code.PERMISSION_DENIED),
      )
    _ = logger.info(s"Acquired primary party of user $serviceUser: $serviceParty")
    _ = logger.info(s"Waiting for templates to be uploaded: ${requiredTemplates}")
    _ <- waitForPackages(connection)
    _ = logger.info(s"Packages available, running app-specific init")
    state <- initialize(ledgerClient, serviceParty)
  } yield state

  logger.info(s"Starting initialization")
  private val ledgerClientF = createLedgerClient()
  private val initializeF = ledgerClientF.flatMap(initializeNode)

  initializeF.onComplete { _ =>
    logger.info(s"Initialization complete, running on version ${BuildInfo.compiledVersion}")
    isInitializedVar.set(true)
  }

  // TODO(M1-92): Cleanup init failures
  initializeF.failed.foreach { err =>
    logger.error(s"Initialization of $name failed", err)
    sys.exit(1)
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    logger.info(s"Stopping $name node")
    Seq(
      AsyncCloseable(s"$name App state", initializeF.map(_.close()), closingTimeout),
      AsyncCloseable(
        s"$name Ledger API connection",
        ledgerClientF.map(_.close()),
        closingTimeout,
      ),
    )
  }
}
