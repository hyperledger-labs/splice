package com.daml.network.environment

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding.TemplateCompanion
import com.daml.ledger.client.configuration.CommandClientConfiguration
import com.daml.network.config.SharedCoinAppParameters
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import com.digitalasset.canton.config.RequireTypes.{InstanceName, Port}
import com.digitalasset.canton.environment.CantonNode
import com.digitalasset.canton.health.admin.data.{NodeStatus, SimpleStatus, TopologyQueueStatus}
import com.digitalasset.canton.lifecycle.{AsyncCloseable, AsyncOrSyncCloseable, FlagCloseableAsync}
import com.digitalasset.canton.logging.{NamedLogging, NamedLoggerFactory}
import com.digitalasset.canton.time.HasUptime
import com.digitalasset.canton.topology.{PartyId, UniqueIdentifier}
import com.digitalasset.canton.tracing.{NoTracing, TracerProvider}
import com.digitalasset.canton.util.retry.{Backoff, Success}
import com.digitalasset.canton.util.retry.RetryUtil.AllExnRetryable
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._

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

  protected val maxRetries: Int = 15

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
    val policy = Backoff(logger, this, maxRetries, 30.millis, 15.seconds, "waitForPackages")
    policy(
      query(),
      AllExnRetryable,
    )

  }

  val ledgerClientF: Future[CoinLedgerClient] =
    // TODO(M1-92) Make creation of ledger client return a Future instead of
    // blocking.
    Future { createLedgerClient(remoteParticipant) }

  val initializeF: Future[State] = for {
    ledgerClient <- ledgerClientF
    connection = ledgerClient.connection(name.toString)
    serviceParty <- connection.retryLedgerApi(
      if (allocateServiceUser) connection.getOrAllocateParty(serviceUser)
      else connection.getPrimaryParty(serviceUser),
      CoinLedgerConnection.RetryOnUserManagementError,
      // Wallet app starts last so we bump the retries here
      maxRetriesO = Some(maxRetries),
    )
    _ <- waitForPackages(connection)
    state <- initialize(ledgerClient, serviceParty)
  } yield state

  initializeF.onComplete { _ =>
    isInitializedVar.set(true)
  }

  // TODO(#885): Cleanup init failures
  initializeF.failed.foreach { err =>
    logger.error(s"Initialization of $name failed with $err")
    sys.exit(1)
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    logger.info(s"Stopping $name node")
    Seq(
      AsyncCloseable(s"$name App state", initializeF.map(_.close()), closingTimeout),
      AsyncCloseable(s"$name Ledger API connection", ledgerClientF.map(_.close()), closingTimeout),
    )
  }

  protected def createLedgerClient(remoteParticipant: RemoteParticipantConfig): CoinLedgerClient =
    CoinLedgerClient(
      remoteParticipant.ledgerApi,
      ApiTypes.ApplicationId(name.unwrap),
      CommandClientConfiguration.default,
      remoteParticipant.token,
      parameters.processingTimeouts,
      loggerFactory,
      tracerProvider,
    )
}
