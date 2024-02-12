package com.daml.network.environment

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.{ConnectionContext, Http}
import org.apache.pekko.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpHeader,
  HttpRequest,
  HttpResponse,
}
import org.apache.pekko.http.scaladsl.server.Directive0
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.CNNodeMetrics
import com.daml.network.admin.api.HttpRequestLogger
import com.daml.network.auth.{AuthToken, AuthTokenManager, AuthTokenSource, AuthTokenSourceNone}
import com.daml.network.config.{CNParticipantClientConfig, SharedCNNodeAppParameters}
import com.daml.network.util.{HasHealth, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.environment.CantonNode
import com.digitalasset.canton.health.admin.data.{NodeStatus, SimpleStatus, TopologyQueueStatus}
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
import com.digitalasset.canton.tracing.{NoTracing, TraceContext, TracerProvider, W3CTraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import scala.collection.immutable
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

/** A running instance of a canton node. See CNNode for the subclass that provides the default initialization for most apps. */
abstract class CNNodeBase[State <: AutoCloseable & HasHealth](
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
) extends CantonNode
    with FlagCloseableAsync
    with HasCloseContext
    with NamedLogging
    with HasUptime
    with NoTracing {
  val name: InstanceName

  protected val retryProvider: RetryProvider =
    RetryProvider(
      loggerFactory,
      parameters.processingTimeouts,
      futureSupervisor,
      nodeMetrics.metricsFactory,
    )

  override val timeouts = parameters.processingTimeouts

  private val isInitializedVar: AtomicReference[Boolean] = new AtomicReference(false)

  protected def isInitialized = isInitializedVar.get()

  protected def packages = DarResources.cantonCoin.all

  lazy private val packageSignatures = {
    ResourceTemplateDecoder.loadPackageSignaturesFromResources(packages)
  }

  lazy protected implicit val templateDecoder: TemplateJsonDecoder =
    new ResourceTemplateDecoder(packageSignatures, loggerFactory)

  private val httpExt = Http()(ac)

  private def traceContextFromHeaders(headers: immutable.Seq[HttpHeader]) = {
    W3CTraceContext
      .fromHeaders(headers.map(h => h.name() -> h.value()).toMap)
      .map(_.toTraceContext)
      .getOrElse(traceContext)
  }
  protected implicit val httpClient: HttpRequest => Future[HttpResponse] = (request: HttpRequest) =>
    {
      val requestTraceCtx = traceContextFromHeaders(request.headers)
      import parameters.loggingConfig.api.*
      val logPayload = messagePayloads
      val pathLimited = request.uri.path.toString
        .limit(maxMethodLength)
      def msg(message: String): String =
        s"HTTP client (${request.method.name} ${pathLimited}): ${message}"

      if (logPayload) {
        request.entity match {
          // Only logging strict messages which are already in memory, not attempting to log streams
          case HttpEntity.Strict(ContentTypes.`application/json`, data) =>
            logger.debug(
              msg(s"Requesting with entity data: ${data.utf8String.limit(maxStringLength)}")
            )(requestTraceCtx)
          case _ => logger.debug(msg(s"omitting logging of request entity data."))(requestTraceCtx)
        }
      }
      logger
        .trace(msg(s"headers: ${request.headers.toString.limit(maxMetadataSize)}"))(requestTraceCtx)
      val host = request.uri.authority.host.address()
      val port = request.uri.effectivePort
      logger.trace(
        s"Connecting to host: ${host}, port: ${port} request.uri = ${request.uri}"
      )(requestTraceCtx)
      val connectionContext = request.uri.scheme match {
        case "https" => ConnectionContext.httpsClient(SSLContext.getDefault)
        case _ => ConnectionContext.noEncryption()
      }
      val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
        Http()
          .outgoingConnectionUsingContext(host, port, connectionContext)

      // A new stream is materialized, creating a new connection for every request. The connection is closed on stream completion (success or failure)
      // There is overhead in doing this, but it is the simplest way to implement a request-timeout.
      def dispatchRequest(request: HttpRequest): Future[HttpResponse] =
        Source
          .single(request)
          .via(connectionFlow)
          .completionTimeout(parameters.requestTimeout.asFiniteApproximation)
          .runWith(Sink.head)
          .recoverWith { case NonFatal(e) =>
            logger.debug(msg("HTTP request failed"), e)(traceContext)
            Future.failed(e)
          }
      val start = System.currentTimeMillis()
      dispatchRequest(request).map { response =>
        val responseTraceCtx = traceContextFromHeaders(response.headers)
        val end = System.currentTimeMillis()
        logger.trace(msg(s"HTTP request took ${end - start} ms to complete"))(responseTraceCtx)
        logger.debug(msg(s"Received response with status code: ${response.status}"))(
          responseTraceCtx
        )
        if (logPayload) {
          response.entity match {
            // Only logging strict messages which are already in memory, not attempting to log streams
            case HttpEntity.Strict(ContentTypes.`application/json`, data) =>
              logger.debug(
                msg(
                  s"Received response with entity data: ${data.utf8String.limit(maxStringLength)}"
                )
              )(responseTraceCtx)
            case _ =>
              logger.debug(msg(s"omitting logging of response entity data."))(responseTraceCtx)
          }
        }
        logger.trace(
          msg(s"Response contains headers: ${response.headers.toString.limit(maxMetadataSize)}")
        )(responseTraceCtx)
        response
      }
    }

  def requestLogger(implicit traceContext: TraceContext): Directive0 =
    HttpRequestLogger(parameters.loggingConfig.api, loggerFactory)

  final def isActive: Boolean = {
    // initialized and the state reports itself as healthy
    isInitialized && initializeF.value.exists(_.toOption.exists(_.isHealthy))
  }

  protected def ports: Map[String, Port]

  // TODO(#736): fork or generalize status definition.
  override final def status: Future[NodeStatus.Status] = {
    val status = SimpleStatus(
      uid = UniqueIdentifier.tryFromProtoPrimitive(s"coin::$name"),
      uptime = uptime(),
      ports = ports,
      active = isActive,
      topologyQueue = TopologyQueueStatus(0, 0, 0),
      // TODO(#3859) Set this to something useful.
      components = Seq.empty,
    )
    Future.successful(status)
  }

  private def createLedgerClient(): Future[CNLedgerClient] = for {
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
              "Acquiring auth token",
              authTokenManager.getToken,
              logger,
            )
      }
    new CNLedgerClient(
      participantClient.ledgerApi.clientConfig,
      // Note: When ledger API auth is enabled, application ID must be equal to user ID
      serviceUser,
      getToken,
      parameters.loggingConfig.api,
      loggerFactory,
      tracerProvider,
      retryProvider,
    )
  }

  private def waitForUser(connection: BaseLedgerConnection): Future[Unit] = {
    logger.info(s"Waiting for user $serviceUser")
    retryProvider.getValueWithRetries(
      RetryFor.WaitingOnInitDependency,
      s"user $serviceUser",
      connection.getUser(serviceUser).map(_ => ()),
      logger,
      additionalCodes = Seq(Status.Code.PERMISSION_DENIED),
    )
  }

  // Code that is run before a ledger connection becomes available.
  // This can be used for helping the participant initialize.
  protected def preInitializeBeforeLedgerConnection(): Future[Unit] = Future.unit

  protected def initializeNode(
      ledgerClient: CNLedgerClient
  ): Future[State]

  private lazy val appInitMessage = s"$name app initialization"

  protected def appInitStep[T](
      description: String
  )(f: => Future[T]): Future[T] =
    TraceContext.withNewTraceContext(implicit tc => {
      logger.debug(s"$appInitMessage: $description started")(tc)
      // TODO(#5419): here we could pass on the trace context to inner function to make sure all log lines
      // produced by this initialization step are tagged with the same trace id.
      // However, some of our helper methods hold onto the trace context and use it for all future
      // operations. This would mean that the trace context would be used for operations that are
      // not part of the initialization step.
      Try(f) match {
        case Success(asyncValue) =>
          asyncValue.transform {
            case result @ Success(_) =>
              logger.info(s"$appInitMessage: $description finished")(tc)
              result
            case Failure(ex) =>
              logger.info(s"$appInitMessage: $description failed", ex)(tc)
              Failure(new RuntimeException(s"$appInitMessage: $description failed", ex))
          }
        case Failure(ex) =>
          logger.info(s"$appInitMessage: $description failed", ex)(tc)
          throw new RuntimeException(s"$appInitMessage: $description failed", ex)
      }
    })

  protected def appInitStepSync[T](
      description: String
  )(f: => T): T = TraceContext.withNewTraceContext(implicit tc => {
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

  logger.info(s"$appInitMessage: Starting initialization")
  private val preInitialize1F = preInitializeBeforeLedgerConnection()
  private val ledgerClientF = preInitialize1F.flatMap { _ =>
    appInitStep("Create ledger client") { createLedgerClient() }
  }
  private val initializeF = ledgerClientF.flatMap { client =>
    val initConnection = client.readOnlyConnection(
      this.getClass.getSimpleName,
      loggerFactory,
    )
    appInitStep("Wait for user") { waitForUser(initConnection) }.flatMap(_ =>
      initializeNode(client)
    )
  }
  private[network] def getState = initializeF.value match {
    case Some(Success(state)) => Some(state)
    case _ => None
  }

  initializeF.foreach { _ =>
    logger.info(
      s"$appInitMessage: Initialization complete, running on version ${BuildInfo.compiledVersion}"
    )
    isInitializedVar.set(true)
  }

  // TODO(tech-debt): Handle cleanup in case some initialization failed mid-way.
  // For example, if we fail to get the service party we won't close the ledger client.
  // Note that we have a similar issue in app-initialization, so this should be handled
  // in a generic way
  val initUnlessClosing = initializeF.andThen {
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

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    logger.info(s"Stopping $name node")
    val nonNegativeDuration = NonNegativeDuration.tryFromDuration(closingTimeout)
    Seq(
      // Close first to trigger the node-wide shutdown signal before shutting down actual services
      SyncCloseable(
        s"$name retry provider",
        retryProvider.close(),
      ),
      // By shutting down this before the app state, any in-flight gRPC connections will be cancelled.
      // This prevents requests that take longer than the shutdown wait from blocking the shutdown process.
      // See #9851.
      AsyncCloseable(
        s"$name Ledger API connection",
        ledgerClientF.map(_.close()),
        nonNegativeDuration,
      ),
      AsyncCloseable(
        s"$name App state",
        initUnlessClosing.transform {
          case Failure(_) if this.isClosing => Success(())
          case Failure(e) => Failure(e)
          case Success(node) => Success(node.close())
        },
        nonNegativeDuration,
      ),
      AsyncCloseable(
        "http pool",
        httpExt.shutdownAllConnectionPools(),
        nonNegativeDuration,
      ),
    )
  }
}
