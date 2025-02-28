// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import better.files.File
import cats.data.EitherT
import com.daml.nameof.NameOf.functionFullName
import org.lfdecentralizedtrust.splice.SpliceMetrics
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.{LocalNodeConfig, ProcessingTimeout}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.crypto.Crypto
import com.digitalasset.canton.environment.{CantonNode, CantonNodeBootstrap, CantonNodeParameters}
import com.digitalasset.canton.lifecycle.{HasCloseContext, LifeCycle}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.StorageFactory
import com.digitalasset.canton.telemetry.ConfiguredOpenTelemetry
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{NoTracing, TracerProvider}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.{Future, blocking}
import org.lfdecentralizedtrust.splice.admin.http.{AdminRoutes, HttpAdminService}

object NodeBootstrap {
  type HealthDumpFunction = () => Future[File]
}

/** Modelled after CantonNodeBootstrap
  */
trait NodeBootstrap[+N <: CantonNode]
    extends CantonNodeBootstrap[N] // TODO(#736): remove the dependency on this trait.
    {

  def name: InstanceName
  def clock: Clock
  def isInitialized: Boolean
  def start(): EitherT[Future, String, Unit]

  /** This function assumes that all configured remote Canton participants are already initialized
    * and connected to at least one domain. This is necessary because the initialization of some Splice apps needs to
    * interact with a Ledger API, e.g., to allocate a party or user.
    */
  def initialize(adminRoutes: AdminRoutes): EitherT[Future, String, Unit]

  def getNode: Option[N]

  // TODO(#736): following methods are only here because of the CantonNodeBootstrap trait
  def crypto: Option[Crypto] = ???

}

/** Bootstrapping class used to drive the initialization of a single Amulet app instance
  *
  * Modelled after CantonNodeBootstrapBase
  *
  * Simplifications compared to CantonNodeBootstrapBase
  * - removed NodeId and NodeId initialization logic
  * - currently no stores (`Storage(Factory)`) are supported
  * - removed replica support
  * - removed all topology commands
  * - removed all crypto key generation
  */
abstract class NodeBootstrapBase[
    T <: CantonNode,
    NodeConfig <: LocalNodeConfig,
    ParameterConfig <: CantonNodeParameters,
](
    nodeConfig: NodeConfig,
    override val name: InstanceName,
    parameterConfig: ParameterConfig,
    val clock: Clock,
    nodeMetrics: SpliceMetrics,
    storageFactory: StorageFactory,
    val loggerFactory: NamedLoggerFactory,
    configuredOpenTelemetry: ConfiguredOpenTelemetry,
)(
    implicit val executionContext: ExecutionContextIdlenessExecutorService,
    implicit val scheduler: ScheduledExecutorService,
    implicit val actorSystem: ActorSystem,
) extends NodeBootstrap[T]
    with HasCloseContext
    with NoTracing {

  protected val tracerProvider = TracerProvider.Factory(configuredOpenTelemetry, name.unwrap)
  implicit val tracer: Tracer = tracerProvider.tracer

  private val isRunningVar = new AtomicBoolean(true)
  protected val storage =
    storageFactory
      .tryCreate(
        connectionPoolForParticipant,
        parameterConfig.loggingConfig.queryCost,
        clock,
        Some(scheduler),
        nodeMetrics.storageMetrics,
        parameterConfig.processingTimeouts,
        loggerFactory,
      )
  protected val httpAdminService: HttpAdminService =
    HttpAdminService(
      nodeConfig.nodeTypeName,
      nodeConfig.adminApi,
      parameterConfig,
      loggerFactory,
      getNode,
    )
  // reference to the node once it has been started
  private val ref: AtomicReference[Option[T]] = new AtomicReference(None)
  private val starting = new AtomicBoolean(false)

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

  // TODO(#736): obviously we don't need this. however, removing this likely requires a Canton upstream change.
  // This absolutely must be a "def", because it is used during class initialization.
  protected def connectionPoolForParticipant: Boolean = false

  override protected val timeouts: ProcessingTimeout = parameterConfig.processingTimeouts

  def isActive: Boolean

  protected val grpcAdminServers: List[AutoCloseable] = List()

  /** Attempt to start the node.
    */
  def start(): EitherT[Future, String, Unit] = {
    initialize(httpAdminService).leftMap { err =>
      logger.info(s"Failed to initialize node, trying to clean up: $err")
      close()
      err
    }
  }

  override def onClosed(): Unit = blocking {
    synchronized {
      if (isRunningVar.getAndSet(false)) {
        val stores = List()
        val instances =
          grpcAdminServers ++ getNode.toList ++ stores ++ List(clock, httpAdminService)
        LifeCycle.close(instances*)(logger)
        logger.debug(s"Successfully completed shutdown of $name")
      } else {
        logger.warn(
          s"Unnecessary second close of node $name invoked. Ignoring it.",
          new Exception("location"),
        )
      }
    }
  }
}
