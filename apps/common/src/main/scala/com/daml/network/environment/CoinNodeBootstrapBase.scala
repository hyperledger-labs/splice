package com.daml.network.environment

import akka.actor.ActorSystem
import cats.data.EitherT
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.configuration.CommandClientConfiguration
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.RequireTypes.InstanceName
import com.digitalasset.canton.config.{LocalNodeConfig, LocalNodeParameters, ProcessingTimeout}
import com.digitalasset.canton.crypto.Crypto
import com.digitalasset.canton.environment.{CantonNode, CantonNodeBootstrap}
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.health.admin.grpc.GrpcStatusService
import com.digitalasset.canton.health.admin.v0.StatusServiceGrpc
import com.digitalasset.canton.lifecycle.{HasCloseContext, Lifecycle}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.metrics.MetricHandle.NodeMetrics
import com.digitalasset.canton.networking.grpc.CantonServerBuilder
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import com.digitalasset.canton.resource.StorageFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.NodeId
import com.digitalasset.canton.tracing.{NoTracing, TracerProvider}
import io.functionmeta.functionFullName
import io.grpc.protobuf.services.ProtoReflectionService
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.{Future, blocking}

/** Modelled after CantonNodeBootstrap
  */
trait CoinNodeBootstrap[+Node <: CantonNode]
    extends CantonNodeBootstrap[Node] // TODO(Arne): remove the dependence on this trait.
    {

  def name: InstanceName
  def clock: Clock
  def isInitialized: Boolean
  def start(): EitherT[Future, String, Unit]

  def initialize: EitherT[Future, String, Unit]

  def getNode: Option[Node]

  // TODO(Arne): following methods are only here because of the CantonNodeBootstrap trait
  def initializeWithProvidedId(id: NodeId): EitherT[Future, String, Unit] = initialize
  def getId: Option[NodeId] = None
  def crypto: Crypto = ???

}

/** Bootstrapping class used to drive the initialization of a single Coin app instance
  *
  * Modelled after CantonNodeBootstrapBase
  *
  * Simplifications compared to CantonNodeBootstrapBase
  * - removed NodeId and NodeId initialization logic
  * - currently no stores (`Storage(Factory)`) are supported
  * - removed replica support
  * - removed all topology commands
  * - removed all crypto key generation
  *
  * TODO(Arne): potentially completely remove separation into initialization and start step.
  */
abstract class CoinNodeBootstrapBase[
    T <: CantonNode,
    NodeConfig <: LocalNodeConfig,
    ParameterConfig <: LocalNodeParameters,
](
    override val name: InstanceName,
    config: NodeConfig,
    parameterConfig: ParameterConfig,
    val clock: Clock,
    nodeMetrics: NodeMetrics,
    storageFactory: StorageFactory,
    val loggerFactory: NamedLoggerFactory,
)(
    implicit val executionContext: ExecutionContextIdlenessExecutorService,
    implicit val actorSystem: ActorSystem,
) extends CoinNodeBootstrap[T]
    with HasCloseContext
    with NoTracing {

  protected val adminApiConfig = config.adminApi
  protected val initConfig = config.init
  protected val tracerProvider = TracerProvider.Factory(parameterConfig.tracing.tracer, name.unwrap)
  implicit val tracer: Tracer = tracerProvider.tracer

  private val isRunningVar = new AtomicBoolean(true)
  protected def isRunning: Boolean = isRunningVar.get()
  protected val dbStorageMetrics = nodeMetrics.dbStorage
  protected val storage =
    storageFactory
      .tryCreate(
        connectionPoolForParticipant,
        parameterConfig.logQueryCost,
        dbStorageMetrics,
        parameterConfig.processingTimeouts,
        loggerFactory,
      )

  // reference to the node once it has been started
  private val ref: AtomicReference[Option[T]] = new AtomicReference(None)
  private val starting = new AtomicBoolean(false)

  // TODO(Arne): revisit.
  /** kick off initialisation during startup */
  protected def startInstanceUnlessClosing(
      instanceET: => EitherT[Future, String, T]
  ): EitherT[Future, String, Unit] = {
    if (isInitialized) {
      logger.warn("Will not start instance again as it is already initialised")
      EitherT.pure[Future, String](())
    } else {
      performUnlessClosingEitherT(functionFullName, "Aborting startup due to shutdown") {
        if (starting.compareAndSet(false, true))
          instanceET.map { instance =>
            val previous = ref.getAndSet(Some(instance))
            // potentially over-defensive, but ensures a runner will not be set twice.
            // if called twice it indicates a bug in initialization.
            previous.foreach { shouldNotBeThere =>
              logger.error(s"Runner has already been set: $shouldNotBeThere")
            }
          }
        else {
          logger.warn("Will not start instance again as it is already starting up")
          EitherT.pure[Future, String](())
        }
      }
    }
  }

  // accessors to both the running node and for testing whether it has been set
  def getNode: Option[T] = ref.get()
  def isInitialized: Boolean = ref.get().isDefined

  // TODO(Arne): obviously we don't need this. however, removing this likely requires a Canton upstream change.
  // This absolutely must be a "def", because it is used during class initialization.
  protected def connectionPoolForParticipant: Boolean = false

  val timeouts: ProcessingTimeout = parameterConfig.processingTimeouts

  protected def isActive: Boolean

  private def status: Future[NodeStatus[NodeStatus.Status]] = {
    getNode
      .map(_.status.map(NodeStatus.Success(_)))
      .getOrElse(Future.successful(NodeStatus.NotInitialized(isActive)))
  }

  // The admin-API services
  logger.info(s"Starting admin-api services on ${adminApiConfig}")
  protected val (adminServer, adminServerRegistry) = {
    val builder = CantonServerBuilder
      .forConfig(
        adminApiConfig,
        nodeMetrics,
        executionContext,
        loggerFactory,
        parameterConfig.loggingConfig.api,
        parameterConfig.tracing,
      )

    val registry = builder.mutableHandlerRegistry()
    val server = builder
      .addService(StatusServiceGrpc.bindService(new GrpcStatusService(status), executionContext))
      .addService(ProtoReflectionService.newInstance(), false)
      .build
      .start()
    (Lifecycle.toCloseableServer(server, logger, "AdminServer"), registry)
  }

  /** Attempt to start the node.
    * TODO(Arne): revisit - should this be merged with the `initialize` function?
    */
  def start(): EitherT[Future, String, Unit] = {
    initialize.leftMap { err =>
      logger.info(s"Failed to initialize node, trying to clean up: $err")
      close()
      err
    }
  }

  override def onClosed(): Unit = blocking {
    synchronized {
      if (isRunningVar.getAndSet(false)) {
        val stores = List()
        val instances = List(
          adminServerRegistry,
          adminServer,
          tracerProvider,
        ) ++ getNode.toList ++ stores ++ List(clock)
        Lifecycle.close(instances: _*)(logger)
        logger.debug(s"Successfully completed shutdown of $name")
      } else {
        logger.warn(
          s"Unnecessary second close of node $name invoked. Ignoring it.",
          new Exception("location"),
        )
      }
    }
  }

  // configuration mostly copied from Canton
  protected def createLedgerConnection(
      remoteParticipant: RemoteParticipantConfig,
      processingTimeout: ProcessingTimeout,
  )(implicit
      sequencerPool: ExecutionSequencerFactory
  ): CoinLedgerConnection = CoinLedgerConnection(
    remoteParticipant.ledgerApi,
    ApiTypes.ApplicationId(name.unwrap),
    10,
    ApiTypes.WorkflowId(name.unwrap),
    CommandClientConfiguration.default, // We use the command submission client instead of the command client
    remoteParticipant.token,
    processingTimeout,
    loggerFactory,
    tracerProvider,
  )
}
