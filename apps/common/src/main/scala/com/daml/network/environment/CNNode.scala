package com.daml.network.environment

import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, HttpResponse}
import com.daml.network.util.{ResourceTemplateDecoder, TemplateJsonDecoder}
import com.daml.network.store.{DomainStore, MultiDomainAcsStore}
import akka.actor.ActorSystem
import akka.http.scaladsl.server.Directive0
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.daml.ledger.javaapi.data.Identifier
import com.daml.network.admin.api.HttpRequestLogger
import com.daml.network.auth.AuthTokenSource
import com.daml.network.config.{CNRemoteParticipantConfig, SharedCNNodeAppParameters}
import com.daml.network.util.HasHealth
import com.digitalasset.canton.DomainAlias
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
import com.digitalasset.canton.topology.{DomainId, PartyId, UniqueIdentifier}
import com.digitalasset.canton.tracing.{NoTracing, TraceContext, TracerProvider}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.{Status, StatusRuntimeException}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Failure
import scala.util.Success

/** A running instance of a canton node */
abstract class CNNode[State <: AutoCloseable & HasHealth](
    serviceUser: String,
    remoteParticipant: CNRemoteParticipantConfig,
    parameters: SharedCNNodeAppParameters,
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

  protected val retryProvider: RetryProvider =
    RetryProvider(loggerFactory, parameters.processingTimeouts)

  override val timeouts = parameters.processingTimeouts

  private val isInitializedVar: AtomicReference[Boolean] = new AtomicReference(false)

  protected def isInitialized = isInitializedVar.get()

  protected val packages = Seq("dar/canton-coin-0.1.0.dar")

  private val packageSignatures =
    ResourceTemplateDecoder.loadPackageSignaturesFromResources(packages)

  protected implicit val templateDecoder: TemplateJsonDecoder =
    new ResourceTemplateDecoder(packageSignatures, loggerFactory)

  private val httpExt = Http()(ac)

  protected implicit val httpClient: HttpRequest => Future[HttpResponse] = (request: HttpRequest) =>
    {
      import parameters.loggingConfig.api.*
      val logPayload = messagePayloads.getOrElse(false)
      val pathLimited = request.uri.path.toString
        .limit(maxMethodLength)
      def msg(message: String): String =
        s"HTTP client ${request.method.name} ${pathLimited}): ${message}"

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
      httpExt.singleRequest(request).map { response =>
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

  /** Templates whose packages must be available before init will run.
    * It it save to omit a template if there is another template in the same
    * package in the set.
    */
  protected def requiredTemplates: Set[Identifier] = Set.empty

  protected def waitForDomainConnection(
      store: DomainStore,
      domain: DomainAlias,
  ): Future[DomainId] = {
    logger.info(show"Waiting for domain $domain to be connected")
    store.signalWhenConnected(domain).map { r =>
      logger.info(show"Connection to domain $domain has been established")
      r
    }
  }

  protected def waitForAcsIngestion(
      store: MultiDomainAcsStore,
      domain: DomainId,
  ): Future[Unit] = {
    logger.info(show"Waiting for ACS ingestion of domain $domain")
    store.signalWhenAcsCompletedOrShutdown(domain).map { _ =>
      logger.info(show"ACS ingestion for domain $domain has been completed")
    }
  }

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

  def initialize(
      ledgerClient: CNLedgerClient,
      party: PartyId,
  ): Future[State]

  private def waitForPackages(connection: CNLedgerConnection): Future[Unit] = {
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

    retryProvider.retryForAutomation("Wait for packages", query(), logger)

  }

  private def createLedgerClient(): Future[CNLedgerClient] = for {
    _ <- Future.successful(())
    _ = logger.info("Creating ledger API auth token source")
    authTokenSource = AuthTokenSource.fromConfig(
      remoteParticipant.ledgerApi.authConfig,
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
      retryProvider,
    )
  }

  private def initializeNode(
      ledgerClient: CNLedgerClient
  ) = for {
    _ <- Future.successful(())
    _ = logger.info(s"Acquiring ledger connection")
    connection = ledgerClient.connection(this.getClass.getSimpleName, loggerFactory)
    _ = logger.info(s"Acquiring primary party of service user $serviceUser")
    serviceParty <-
      retryProvider.retryForAutomation(
        "Querying primary party of user",
        connection.getPrimaryParty(serviceUser),
        logger,
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
  private val initializeF = ledgerClientF.flatMap { client =>
    initializeNode(client)
  }

  initializeF.onComplete { _ =>
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
    case Failure(err) => {
      logger.error(s"Initialization of $name failed", err)
      sys.exit(1)
    }
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
