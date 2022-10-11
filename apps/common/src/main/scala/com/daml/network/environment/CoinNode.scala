package com.daml.network.environment

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding.TemplateCompanion
import com.daml.ledger.client.configuration.CommandClientConfiguration
import com.daml.network.config.SharedCoinAppParameters
import com.digitalasset.canton.config.RequireTypes.{InstanceName, Port}
import com.digitalasset.canton.environment.CantonNode
import com.digitalasset.canton.health.admin.data.{NodeStatus, SimpleStatus, TopologyQueueStatus}
import com.digitalasset.canton.lifecycle.{AsyncCloseable, AsyncOrSyncCloseable, FlagCloseableAsync}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import com.digitalasset.canton.time.HasUptime
import com.digitalasset.canton.topology.{PartyId, UniqueIdentifier}
import com.digitalasset.canton.tracing.{NoTracing, TracerProvider}
import com.digitalasset.canton.util.retry.RetryUtil.AllExnRetryable
import com.digitalasset.canton.util.retry.{Backoff, Success}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}

/** A running instance of a canton node */
abstract class CoinNode[State <: AutoCloseable](
    serviceUser: String,
    remoteParticipant: RemoteParticipantConfig,
    parameters: SharedCoinAppParameters,
    loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
) extends CantonNode
    with CoinRetries
    with FlagCloseableAsync
    with NamedLogging
    with HasUptime
    with NoTracing {
  val name: InstanceName

  override val timeouts = parameters.processingTimeouts

  private val isInitializedVar: AtomicReference[Boolean] = new AtomicReference(false)

  protected def isInitialized = isInitializedVar.get()

  protected def isActive: Boolean = isInitialized

  protected val ports: Map[String, Port]

  /** Templates whose packages must be available before init will run.
    * It it save to omit a template if there is another template in the same
    * package in the set.
    */
  protected val requiredTemplates: Set[TemplateCompanion[_]]

  // TODO(i736): fork or generalize status definition.
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

  // Whether the service user should be allocated by the app itself.
  // We use that in the SVC app at the moment.
  protected val allocateServiceUser: Boolean = false

  def initialize(ledgerClient: CoinLedgerClient, party: PartyId): Future[State]

  private def waitForPackages(connection: CoinLedgerConnection): Future[Unit] = {
    val requiredPackageIds: Set[String] =
      requiredTemplates.map(t => ApiTypes.TemplateId.unwrap(t.id).packageId)

    def query(): Future[Unit] = for {
      packages <- (connection.listPackages(): Future[Set[String]])
    } yield {
      val actual = packages
      val missing = requiredPackageIds -- actual
      if (missing.isEmpty) {
        ()
      } else {
        throw new RuntimeException(s"Missing packages $missing, got $actual")
      }
    }

    implicit val success = Success.always
    val policy = Backoff(logger, this, maxRetries, initialDelay, maxDelay, "waitForPackages")
    policy(
      query(),
      AllExnRetryable,
    )

  }

  val ledgerClientF: Future[CoinLedgerClient] =
    retry("Acquiring coin ledger client", createLedgerClient(remoteParticipant))

  val initializeF: Future[State] = for {
    _ <- Future.successful(logger.info(s"Starting initialization"))
    _ = logger.info(s"Acquiring ledger connection")
    ledgerClient <- ledgerClientF
    connection = ledgerClient.connection(name.toString)
    _ = logger.info(s"Acquiring primary party of service user $serviceUser")
    serviceParty <-
      if (allocateServiceUser)
        retry("Allocating user and party", connection.getOrAllocateParty(serviceUser))
      else retry("Querying primary party of user", connection.getPrimaryParty(serviceUser))
    _ = logger.info(s"Acquired primary party of user $serviceUser: $serviceParty")
    _ = logger.info(s"Waiting for templates to be uploaded: ${requiredTemplates.map(_.id)}")
    _ <- waitForPackages(connection)
    _ = logger.info(s"Packages available, running app-specific init")
    state <- initialize(ledgerClient, serviceParty)
    _ <- Future.successful(logger.info(s"Initialization complete"))
  } yield state

  initializeF.onComplete { _ =>
    isInitializedVar.set(true)
  }

  // TODO(#885): Cleanup init failures
  initializeF.failed.foreach { err =>
    logger.error(s"Initialization of $name failed", err)
    sys.exit(1)
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    logger.info(s"Stopping $name node")
    Seq(
      AsyncCloseable(s"$name App state", initializeF.map(_.close()), closingTimeout),
      AsyncCloseable(s"$name Ledger API connection", ledgerClientF.map(_.close()), closingTimeout),
    )
  }

  protected def createLedgerClient(
      remoteParticipant: RemoteParticipantConfig
  ): Future[CoinLedgerClient] =
    CoinLedgerClient.create(
      remoteParticipant.ledgerApi,
      ApiTypes.ApplicationId(name.unwrap),
      CommandClientConfiguration.default,
      remoteParticipant.token,
      parameters.processingTimeouts,
      loggerFactory,
      tracerProvider,
    )
}
