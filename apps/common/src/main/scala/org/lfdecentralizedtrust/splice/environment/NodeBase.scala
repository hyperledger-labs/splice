// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.canton.auth.CantonAdminTokenDispenser
import org.lfdecentralizedtrust.splice.SpliceMetrics
import org.lfdecentralizedtrust.splice.admin.api.HttpRequestLogger
import org.lfdecentralizedtrust.splice.auth.{
  AuthToken,
  AuthTokenManager,
  AuthTokenSource,
  AuthTokenSourceNone,
}
import org.lfdecentralizedtrust.splice.automation.AutomationService
import org.lfdecentralizedtrust.splice.config.{
  BaseParticipantClientConfig,
  SharedSpliceAppParameters,
}
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.util.{
  HasHealth,
  ResourceTemplateDecoder,
  TemplateJsonDecoder,
}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.environment.CantonNode
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  HasCloseContext,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.{HasUptime, WallClock}
import com.digitalasset.canton.topology.UniqueIdentifier
import com.digitalasset.canton.tracing.{Spanning, TraceContext, TracerProvider}
import com.digitalasset.canton.version.ReleaseVersion
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directive0
import java.time
import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.nowarn
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}

/** A running instance of a canton node. See Node for the subclass that provides the default initialization for most apps. */
abstract class NodeBase[State <: AutoCloseable & HasHealth](
    serviceUser: String,
    participantClient: BaseParticipantClientConfig,
    parameters: SharedSpliceAppParameters,
    loggerFactory: NamedLoggerFactory,
    tracerProvider: TracerProvider,
    futureSupervisor: FutureSupervisor,
    nodeMetrics: SpliceMetrics,
)(implicit
    ac: ActorSystem,
    ec: ExecutionContextExecutor,
    esf: ExecutionSequencerFactory,
    tracer: Tracer,
) extends CantonNode
    with FlagCloseableAsync
    with HasCloseContext
    with NamedLogging
    with HasUptime
    with Spanning {

  val name: InstanceName

  // Not used for splice
  override def adminTokenDispenser: CantonAdminTokenDispenser = ???

  protected val retryProvider: RetryProvider =
    RetryProvider(
      loggerFactory,
      parameters.processingTimeouts,
      futureSupervisor,
      nodeMetrics.openTelemetryMetricsFactory,
    )

  override val timeouts = parameters.processingTimeouts

  private val isInitializedVar: AtomicReference[Boolean] = new AtomicReference(false)

  protected def isInitialized = isInitializedVar.get()

  protected def packagesForJsonDecoding =
    DarResources.amulet.all ++ DarResources.TokenStandard.allPackageResources.flatMap(_.all)

  lazy private val packageSignatures = {
    ResourceTemplateDecoder.loadPackageSignaturesFromResources(packagesForJsonDecoding)
  }

  lazy protected implicit val templateDecoder: TemplateJsonDecoder =
    new ResourceTemplateDecoder(packageSignatures, loggerFactory)

  private val httpExt = Http()(ac)

  protected implicit val httpClient: HttpClient = HttpClient(
    parameters.monitoringConfig.logging.api,
    HttpClient.HttpRequestParameters(parameters.requestTimeout),
    logger,
  )

  def requestLogger(implicit traceContext: TraceContext): Directive0 =
    HttpRequestLogger(parameters.loggingConfig.api, loggerFactory)

  final def isActive: Boolean = {
    // initialized and the state reports itself as healthy
    isInitialized && initializeF.value.exists(_.toOption.exists(_.isHealthy))
  }

  protected def ports: Map[String, Port]

  override type Status = NodeBase.NodeStatus

  // TODO(DACH-NY/canton-network-node#736): fork or generalize status definition.
  override final def status = {
    NodeBase.NodeStatus(
      uid = UniqueIdentifier.tryFromProtoPrimitive(s"amulet::$name"),
      uptime = uptime(),
      ports = ports,
      active = isActive,
      version = ReleaseVersion.tryCreate(BuildInfo.compiledVersion),
    )
  }

  private def createLedgerClient()(implicit tc: TraceContext): Future[SpliceLedgerClient] = for {
    _ <- Future.successful(())
    _ = logger.info("Creating ledger API auth token source")
    authTokenSource = AuthTokenSource.fromConfig(
      participantClient.ledgerApi.authConfig,
      loggerFactory,
    )
  } yield {
    // AuthTokenSourceNone source does not work with AuthTokenManager which re-requests on None result
    val getToken: () => Future[Option[AuthToken]] =
      authTokenSource match {
        case none: AuthTokenSourceNone => () => none.getToken
        case other =>
          val clock = new WallClock(timeouts, loggerFactory)
          val authTokenManager = new AuthTokenManager(
            () => other.getToken,
            this.isClosing,
            clock,
            loggerFactory,
          )
          () =>
            retryProvider.retry(
              RetryFor.WaitingOnInitDependency,
              "acquire_auth_token",
              "Acquiring auth token",
              authTokenManager.getToken,
              logger,
            )
      }
    new SpliceLedgerClient(
      participantClient.ledgerApi.clientConfig,
      // Note: When ledger API auth is enabled, application ID must be equal to user ID
      serviceUser,
      getToken,
      parameters.loggingConfig.api,
      loggerFactory,
      tracerProvider,
      retryProvider,
      nodeMetrics.grpcClientMetrics,
    )
  }

  private def waitForUser(
      connection: BaseLedgerConnection
  )(implicit tc: TraceContext): Future[Unit] = {
    logger.info(s"Waiting for user $serviceUser")
    retryProvider.getValueWithRetries(
      RetryFor.WaitingOnInitDependency,
      "wait_user",
      s"user $serviceUser",
      connection.getUser(serviceUser).map(_ => ()),
      logger,
      additionalCodes = Seq(Status.Code.PERMISSION_DENIED),
    )
  }

  // Code that is run before a ledger connection becomes available.
  // This can be used for helping the participant initialize.
  protected def preInitializeBeforeLedgerConnection()(implicit
      @nowarn("cat=unused") tc: TraceContext
  ): Future[Unit] =
    Future.unit

  protected def initializeNode(
      ledgerClient: SpliceLedgerClient
  )(implicit tc: TraceContext): Future[State]

  private lazy val appInitMessage = s"$name app initialization"

  protected def appInitStep[T](
      description: String
  )(f: => Future[T])(implicit tc: TraceContext): Future[T] =
    withSpan(s"init $description")(implicit tc =>
      _ => {
        val startTime = Instant.now()
        logger.debug(s"$appInitMessage: $description started")(tc)
        // TODO(DACH-NY/canton-network-node#5419): here we could pass on the trace context to inner function to make sure all log lines
        // produced by this initialization step are tagged with the same trace id.
        // However, some of our helper methods hold onto the trace context and use it for all future
        // operations. This would mean that the trace context would be used for operations that are
        // not part of the initialization step.
        Try(f) match {
          case Success(asyncValue) =>
            asyncValue.transform {
              case result @ Success(_) =>
                logger.info(s"$appInitMessage: $description finished after ${time.Duration
                    .between(startTime, Instant.now())
                    .toString}")(tc)
                result
              case Failure(ex) =>
                logger.info(s"$appInitMessage: $description failed", ex)(tc)
                Failure(new RuntimeException(s"$appInitMessage: $description failed", ex))
            }
          case Failure(ex) =>
            logger.info(s"$appInitMessage: $description failed", ex)(tc)
            throw new RuntimeException(s"$appInitMessage: $description failed", ex)
        }
      }
    )

  protected def appInitStepSync[T](
      description: String
  )(f: => T): T = TraceContext.withNewTraceContext(description)(implicit tc => {
    logger.info(s"$appInitMessage: $description started")(tc)
    // See note about trace context in appInitStep
    Try(f) match {
      case Success(value) =>
        logger.info(s"$appInitMessage: $description finished")(tc)
        value
      case Failure(ex) =>
        logger.info(s"$appInitMessage: $description failed", ex)(tc)
        throw new RuntimeException(s"$appInitMessage: $description failed", ex)
    }
  })

  private lazy val ledgerClientF = appInitStep("create ledger client") {
    createLedgerClient()(TraceContext.empty)
  }(TraceContext.empty)

  private val initializeF = withNewTrace("app_init") { implicit tc => _ =>
    logger.info(s"$appInitMessage: Starting initialization")
    val preInitialize1F = preInitializeBeforeLedgerConnection()
    val ledgerClient = preInitialize1F.flatMap { _ =>
      ledgerClientF
    }
    appInitStep("Initialize app") {
      ledgerClient.flatMap { client =>
        val initConnection = client.readOnlyConnection(
          this.getClass.getSimpleName,
          loggerFactory,
        )
        appInitStep("Wait for user") { waitForUser(initConnection) }.flatMap(_ =>
          appInitStep("Initialize node") { initializeNode(client) }
        )
      }
    }
      // TODO(tech-debt): Handle cleanup in case some initialization failed mid-way.
      // For example, if we fail to get the service party we won't close the ledger client.
      // Note that we have a similar issue in app-initialization, so this should be handled
      // in a generic way
      .andThen {
        case Success(_) =>
          logger.info(
            s"$appInitMessage: Initialization complete, running on version ${BuildInfo.compiledVersion}"
          )
          isInitializedVar.set(true)
        case Failure(err) if this.isClosing =>
          logger.info(
            s"$appInitMessage: Ignoring initialization failure, we are actually shutting down. Message was: ${err.getMessage()}"
          )
        case Failure(err) =>
          val msg = s"$appInitMessage: Initialization failed"
          logger.error(msg, err)
          System.err.println(s"$msg, so exiting; check the application logs for details")
          err.printStackTrace()
          sys.exit(1)
      }
  }

  private[splice] def getState = initializeF.value match {
    case Some(Success(state)) => Some(state)
    case _ => None
  }

  for {
    state <- initializeF
    automation = automationServices(state)
  } AutomationService.checkPausedTriggersSpelling(automation)(TraceContext.empty)

  protected[this] def automationServices(st: State): Seq[AutomationService.OrCompanion]

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    withNewTrace("closing") { implicit tc => _ =>
      logger.info(s"Stopping $name node")
      val nonNegativeDuration = NonNegativeDuration.tryFromDuration(closingTimeout)
      Seq(
        // Close first to trigger the node-wide shutdown signal before shutting down actual services
        SyncCloseable(
          s"$name retry provider",
          retryProvider.close(),
        ),
        // By shutting down gRPC (ledger) and HTTP connections before the app state, any in-flight connections will be cancelled.
        // This prevents requests that take longer than the shutdown wait from blocking the shutdown process.
        // See #9851 and #10167.
        AsyncCloseable(
          s"$name Ledger API connection",
          ledgerClientF.map(_.close()),
          nonNegativeDuration,
        ),
        AsyncCloseable(
          "http pool",
          httpExt.shutdownAllConnectionPools(),
          nonNegativeDuration,
        ),
        AsyncCloseable(
          s"$name App state",
          initializeF.transform {
            case Failure(_) if this.isClosing => Success(())
            case Failure(e) => Failure(e)
            case Success(node) => Success(node.close())
          },
          nonNegativeDuration,
        ),
      )
    }
  }
}

object NodeBase {
  final case class NodeStatus(
      uid: UniqueIdentifier,
      uptime: Duration,
      ports: Map[String, Port],
      active: Boolean,
      version: ReleaseVersion,
  ) extends com.digitalasset.canton.health.admin.data.NodeStatus.Status {

    override val components: Seq[Nothing] = Seq.empty

    // Doesn't matter for splice but is required by the trait
    override val topologyQueue =
      com.digitalasset.canton.health.admin.data.TopologyQueueStatus(0, 0, 0)

    override def pretty =
      prettyOfString(_ =>
        Seq(
          s"Splice node id: ${uid.toProtoPrimitive}",
          show"Uptime: $uptime",
          s"Ports: ${com.digitalasset.canton.health.admin.data.NodeStatus.portsString(ports)}",
          s"Active: $active",
          s"Version: $version",
        ).mkString(System.lineSeparator())
      )
  }
}
