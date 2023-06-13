package com.daml.network.environment

import akka.actor.ActorSystem
import akka.http.scaladsl.{ConnectionContext, Http}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse}
import akka.http.scaladsl.server.Directive0
import akka.stream.scaladsl.{Flow, Sink, Source}
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.network.admin.api.HttpRequestLogger
import com.daml.network.auth.AuthTokenSource
import com.daml.network.config.{CNParticipantClientConfig, SharedCNNodeAppParameters}
import com.daml.network.util.{HasHealth, ResourceTemplateDecoder, TemplateJsonDecoder}
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.environment.CantonNode
import com.digitalasset.canton.health.admin.data.{NodeStatus, SimpleStatus, TopologyQueueStatus}
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  FlagCloseableAsync,
  SyncCloseable,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.HasUptime
import com.digitalasset.canton.topology.UniqueIdentifier
import com.digitalasset.canton.tracing.{NoTracing, TraceContext, TracerProvider}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

/** A running instance of a canton node. See CNNode for the subclass that provides the default initialization for most apps. */
abstract class CNNodeBase[State <: AutoCloseable & HasHealth](
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
) extends CantonNode
    with FlagCloseableAsync
    with NamedLogging
    with HasUptime
    with NoTracing {
  val name: InstanceName

  protected val retryProvider: RetryProvider =
    RetryProvider(loggerFactory, parameters.processingTimeouts, futureSupervisor)

  override val timeouts = parameters.processingTimeouts

  private val isInitializedVar: AtomicReference[Boolean] = new AtomicReference(false)

  protected def isInitialized = isInitializedVar.get()

  protected val packages = Seq("dar/canton-coin-0.1.0.dar")

  lazy private val packageSignatures = {
    ResourceTemplateDecoder.loadPackageSignaturesFromResources(packages)
  }

  lazy protected implicit val templateDecoder: TemplateJsonDecoder =
    new ResourceTemplateDecoder(packageSignatures, loggerFactory)

  private val httpExt = Http()(ac)

  protected implicit val httpClient: HttpRequest => Future[HttpResponse] = (request: HttpRequest) =>
    {
      import parameters.loggingConfig.api.*
      val logPayload = messagePayloads.getOrElse(false)
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
            )
          case _ => logger.debug(msg(s"omitting logging of request entity data."))
        }
      }
      logger.trace(msg(s"headers: ${request.headers.toString.limit(maxMetadataSize)}"))
      val host = request.uri.authority.host.address()
      val port = request.uri.effectivePort
      logger.trace(
        s"Connecting to host: ${host}, port: ${port} request.uri = ${request.uri}"
      )
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
        val end = System.currentTimeMillis()
        logger.trace(msg(s"HTTP request took ${end - start} ms to complete"))
        logger.debug(msg(s"Received response with status code: ${response.status}"))
        if (logPayload) {
          response.entity match {
            // Only logging strict messages which are already in memory, not attempting to log streams
            case HttpEntity.Strict(ContentTypes.`application/json`, data) =>
              logger.debug(
                msg(
                  s"Received response with entity data: ${data.utf8String.limit(maxStringLength)}"
                )
              )
            case _ => logger.debug(msg(s"omitting logging of response entity data."))
          }
        }
        logger.trace(
          msg(s"Response contains headers: ${response.headers.toString.limit(maxMetadataSize)}")
        )
        response
      }
    }
  def requestLogger(implicit traceContext: TraceContext): Directive0 =
    HttpRequestLogger(parameters.loggingConfig.api, loggerFactory)

  def isActive: Boolean = {
    // initialized and the state reports itself as healthy
    isInitialized && initializeF.value.exists(_.toOption.exists(_.isHealthy))
  }

  protected def ports: Map[String, Port]

  // TODO(#736): fork or generalize status definition.
  override def status: Future[NodeStatus.Status] = {
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
    token <- retryProvider.retryForAutomation(
      "Acquiring initial auth token",
      authTokenSource.getToken,
      logger,
    )
  } yield {
    logger.debug(s"Using token $token for this ledger client")
    new CNLedgerClient(
      participantClient.ledgerApi.clientConfig,
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
      retryProvider,
    )
  }

  private def waitForUser(connection: CNLedgerConnection): Future[Unit] = {
    logger.info(s"Waiting for user $serviceUser")
    retryProvider.getValueWithRetries(
      s"user $serviceUser",
      connection.getUser(serviceUser).map(_ => ()),
      logger,
      additionalCodes = Seq(Status.Code.PERMISSION_DENIED),
    )
  }

  protected def initializeNode(
      ledgerClient: CNLedgerClient
  ): Future[State]

  logger.info(s"Starting initialization")
  private val ledgerClientF = createLedgerClient()
  private val initializeF = ledgerClientF.flatMap { client =>
    val initConnection = client.connection(this.getClass.getSimpleName, loggerFactory)
    waitForUser(initConnection).flatMap(_ => initializeNode(client))
  }

  initializeF.foreach { _ =>
    logger.info(s"Initialization complete, running on version ${BuildInfo.compiledVersion}")
    isInitializedVar.set(true)
  }

  // TODO(tech-debt): Handle cleanup in case some initialization failed mid-way.
  // For example, if we fail to get the service party we won't close the ledger client.
  // Note that we have a similar issue in app-initialization, so this should be handled
  // in a generic way
  val initUnlessClosing = initializeF.andThen {
    case Failure(err) if this.isClosing =>
      logger.info(
        s"Ignoring initialization failure, we are actually shutting down. Message was: ${err.getMessage()}"
      )
    case Failure(err) =>
      val msg = s"Initialization of $name failed"
      logger.error(msg, err)
      System.err.println(s"$msg, so exiting; check the application logs for details")
      sys.exit(1)
  }

  override def closeAsync(): Seq[AsyncOrSyncCloseable] = {
    logger.info(s"Stopping $name node")
    Seq(
      // Close first to trigger the node-wide shutdown signal before shutting down actual services
      SyncCloseable(
        s"$name retry provider",
        retryProvider.close(),
      ),
      AsyncCloseable(
        s"$name App state",
        initUnlessClosing.transform {
          case Failure(_) if this.isClosing => Success(())
          case Failure(e) => Failure(e)
          case Success(node) => Success(node.close())
        },
        closingTimeout,
      ),
      AsyncCloseable(
        s"$name Ledger API connection",
        ledgerClientF.map(_.close()),
        closingTimeout,
      ),
      AsyncCloseable(
        "http pool",
        httpExt.shutdownAllConnectionPools(),
        closingTimeout,
      ),
    )
  }
}
