package com.daml.network.environment

import akka.actor.ActorSystem
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.configuration.CommandClientConfiguration
import com.daml.network.config.SharedCoinAppParameters
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import com.digitalasset.canton.config.RequireTypes.{InstanceName, Port}
import com.digitalasset.canton.environment.CantonNode
import com.digitalasset.canton.health.admin.data.{NodeStatus, SimpleStatus, TopologyQueueStatus}
import com.digitalasset.canton.lifecycle.{AsyncCloseable, AsyncOrSyncCloseable, FlagCloseableAsync}
import com.digitalasset.canton.logging.{NamedLogging, NamedLoggerFactory}
import com.digitalasset.canton.time.HasUptime
import com.digitalasset.canton.topology.UniqueIdentifier
import com.digitalasset.canton.tracing.{NoTracing, TracerProvider}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}

/** A running instance of a canton node */
abstract class CoinNode[State <: AutoCloseable](
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

  def initialize(): Future[State]

  val initializeF: Future[State] = initialize()

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
    Seq(AsyncCloseable(s"$name App state", initializeF.map(_.close()), closingTimeout))
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
