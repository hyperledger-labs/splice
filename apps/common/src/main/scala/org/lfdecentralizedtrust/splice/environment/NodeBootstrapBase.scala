// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import cats.data.EitherT
import com.daml.nameof.NameOf.functionFullName
import org.lfdecentralizedtrust.splice.SpliceMetrics
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.{DbConfig, ProcessingTimeout}
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.crypto.Crypto
import com.digitalasset.canton.environment.{CantonNode, CantonNodeBootstrap, CantonNodeParameters}
import com.digitalasset.canton.lifecycle.{HasCloseContext, LifeCycle, UnlessShutdown}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.{DbMigrations, DbStorage, StorageFactory}
import com.digitalasset.canton.resource.DbStorage.RetryConfig
import com.digitalasset.canton.telemetry.ConfiguredOpenTelemetry
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.tracing.{NoTracing, TracerProvider}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import slick.jdbc.canton.ActionBasedSQLInterpolation.Implicits.actionBasedSQLInterpolationCanton

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.concurrent.{blocking, Future}
import scala.util.{Failure, Success}
import org.lfdecentralizedtrust.splice.admin.http.{AdminRoutes, HttpAdminService}
import org.lfdecentralizedtrust.splice.config.SpliceBackendConfig

/** Modelled after CantonNodeBootstrap
  */
trait NodeBootstrap[+N <: CantonNode]
    extends CantonNodeBootstrap[
      N
    ] // TODO(DACH-NY/canton-network-node#736): remove the dependency on this trait.
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

  // TODO(DACH-NY/canton-network-node#736): following methods are only here because of the CantonNodeBootstrap trait
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
    NodeConfig <: SpliceBackendConfig,
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
      ) match {
      case storage: DbStorage => storage
      case storageType => throw new RuntimeException(s"Unsupported storage type $storageType")
    }
  protected val httpAdminService: HttpAdminService =
    HttpAdminService(
      nodeConfig.nodeTypeName,
      nodeConfig.adminApi,
      parameterConfig,
      parameterConfig.loggingConfig.api,
      nodeConfig.parameters.rateLimiting.clientIpHeaders,
      loggerFactory,
      getNode,
    )
  // reference to the node once it has been started
  private val ref: AtomicReference[Option[T]] = new AtomicReference(None)
  private val starting = new AtomicBoolean(false)

  /** kick off initialisation during startup */
  protected def startInstanceUnlessClosing(
      instance: => T
  ): EitherT[Future, String, Unit] = {
    if (isInitialized) {
      logger.warn("Will not start instance again as it is already initialised")
      EitherT.pure[Future, String](())
    } else {
      val unlessShutdown: com.digitalasset.canton.lifecycle.UnlessShutdown[scala.util.Try[Unit]] =
        synchronizeWithClosingSync(functionFullName)(scala.util.Try {
          if (starting.compareAndSet(false, true)) {
            val previous = ref.getAndSet(Some(instance))
            // potentially over-defensive, but ensures a runner will not be set twice.
            // if called twice it indicates a bug in initialization.
            previous.foreach { shouldNotBeThere =>
              logger.error(s"Runner has already been set: $shouldNotBeThere")
            }
          } else {
            logger.warn("Will not start instance again as it is already starting up")
            ()
          }
        })
      EitherT.fromEither(
        unlessShutdown
          .toRight("Aborting startup due to shutdown")
          .flatMap(_.toEither.left.map(_.toString))
      )
    }
  }

  // accessors to both the running node and for testing whether it has been set
  def getNode: Option[T] = ref.get()
  def isInitialized: Boolean = ref.get().isDefined

  // TODO(DACH-NY/canton-network-node#736): obviously we don't need this. however, removing this likely requires a Canton upstream change.
  // This absolutely must be a "def", because it is used during class initialization.
  protected def connectionPoolForParticipant: Boolean = false

  override protected val timeouts: ProcessingTimeout = parameterConfig.processingTimeouts

  def isActive: Boolean

  protected val grpcAdminServers: List[AutoCloseable] = List()

  /** Attempt to start the node.
    */
  def start(): EitherT[Future, String, Unit] = {
    warnIfDataChecksumsDisabled()
    (for {
      _ <- EitherT(Future(checkAndMigrateDatabase()))
      _ <- initialize(httpAdminService)
    } yield ()).leftMap { err =>
      logger.info(s"Failed to initialize node, trying to clean up: $err")
      close()
      err
    }
  }

  /** Canton used to run this in `ManagedNodes` before creating the node; it now runs it as a
    * bootstrap stage of `CantonNodeBootstrapImpl`, which Splice nodes don't extend.
    * Migrates a fresh database, or any database if `migrate-and-start` is set.
    */
  private def checkAndMigrateDatabase(): Either[String, Unit] =
    nodeConfig.storage match {
      case dbConfig: DbConfig =>
        logger.info(s"Setting up database schemas for $name")
        val retryConfig =
          if (dbConfig.parameters.failFastOnStartup) RetryConfig.failFast
          else RetryConfig.forever
        DbMigrations
          .create(
            dbConfig,
            devVersionSupport = parameterConfig.devVersionSupport,
            timeouts,
            loggerFactory,
          )
          .checkAndMigrate(parameterConfig, retryConfig)
          .leftMap(err => s"Failed to check and migrate database schemas for $name: $err")
          .value
          .onShutdown(Left(s"DB migration check for $name interrupted due to shutdown"))
      case _ => Right(())
    }

  private def warnIfDataChecksumsDisabled(): Unit =
    storage
      .query(
        sql"show data_checksums".as[String].headOption,
        "checkDataChecksumsEnabled",
      )
      .onComplete {
        case Success(UnlessShutdown.Outcome(Some(value))) if value.trim.toLowerCase == "on" =>
          logger.info("PostgreSQL data checksums are enabled.")
        case Success(UnlessShutdown.Outcome(value)) =>
          logger.warn(
            s"PostgreSQL data checksums are not enabled on the database (result: $value). See https://www.postgresql.org/docs/current/checksums.html"
          )
        case Success(UnlessShutdown.AbortedDueToShutdown) =>
          ()
        case Failure(ex) =>
          logger.warn(
            s"Could not determine whether PostgreSQL data checksums are enabled on the database.",
            ex,
          )
      }

  @SuppressWarnings(Array("com.digitalasset.canton.RequireBlocking"))
  override def onClosed(): Unit = blocking {
    synchronized {
      if (isRunningVar.getAndSet(false)) {
        val stores = List()
        val instances =
          List(httpAdminService) ++ grpcAdminServers ++ getNode.toList ++ stores ++ List(clock)
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

  // unused in splice
  override def registerDeclarativeChangeTrigger(trigger: () => Unit): Unit = ???
}
