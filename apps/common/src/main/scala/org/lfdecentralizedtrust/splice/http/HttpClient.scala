// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.daml.metrics.api.MetricQualification.Latency
import com.daml.metrics.api.{MetricHandle, MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.config.{ApiLoggingConfig, NonNegativeDuration}
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.tracing.{TraceContext, W3CTraceContext}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.{ClientTransport, ConnectionContext, Http}
import org.apache.pekko.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpHeader,
  HttpRequest,
  HttpResponse,
}
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import com.digitalasset.canton.util.ShowUtil.*
import org.apache.pekko.http.scaladsl.model.headers.BasicHttpCredentials
import org.apache.pekko.http.scaladsl.settings.{ClientConnectionSettings, HttpsProxySettings}
import org.lfdecentralizedtrust.splice.auth.{AuthToken, AuthTokenSource}
import org.lfdecentralizedtrust.splice.config.AuthTokenSourceConfig

import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

trait HttpClient {
  val requestParameters: HttpClient.HttpRequestParameters
  def withOverrideParameters(newParameters: HttpClient.HttpRequestParameters): HttpClient
  def executeRequest(clientName: String, operationName: String)(request: HttpRequest): Future[HttpResponse]
  def getToken(authConfig: AuthTokenSourceConfig): Future[Option[AuthToken]]
}

object HttpClient {
  case class HttpRequestParameters(requestTimeout: NonNegativeDuration)
  class HttpClientImpl(
      apiLoggingConfig: ApiLoggingConfig,
      outerRequestParameters: HttpClient.HttpRequestParameters,
      httpClientMetrics: HttpClientMetrics,
      logger: TracedLogger,
  )(implicit ac: ActorSystem, ec: ExecutionContext)
      extends HttpClient {
    private val metrics = httpClientMetrics
    override val requestParameters: HttpClient.HttpRequestParameters = outerRequestParameters

    override def withOverrideParameters(
        newParameters: HttpClient.HttpRequestParameters
    ): HttpClient = HttpClient(apiLoggingConfig, newParameters, httpClientMetrics, logger)

    def getToken(
        authConfig: AuthTokenSourceConfig
    ): Future[Option[AuthToken]] = {
      implicit val traceContext = TraceContext.empty
      val loggerFactory = NamedLoggerFactory.root
      val authTokenSource = AuthTokenSource.fromConfig(
        authConfig,
        httpClientMetrics,
        loggerFactory,
      )
      authTokenSource.getToken(traceContext)
    }

    override def executeRequest(clientName: String, operationName: String)(request: HttpRequest): Future[HttpResponse] = {
      implicit val traceContext: TraceContext = traceContextFromHeaders(request.headers)
      import apiLoggingConfig.*
      val logPayload = messagePayloads
      val pathLimited = request.uri.path.toString
        .limit(maxMethodLength)
      def msg(message: String): String =
        s"HTTP client (${request.method.name} ${pathLimited}): ${message}"

      implicit val mc: MetricsContext = HttpClientMetrics.context(clientName, operationName, request.uri.authority.host.address)

      val timing = metrics.startTiming()
      metrics.incInFlight()

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
      logger
        .trace(msg(s"headers: ${request.headers.toString.limit(maxMetadataSize)}"))
      val host = request.uri.authority.host.address()
      val port = request.uri.effectivePort
      logger.trace(
        s"Connecting to host: ${host}, port: ${port} request.uri = ${request.uri}"
      )
      val connectionContext = request.uri.scheme match {
        case "https" => ConnectionContext.httpsClient(SSLContext.getDefault)
        case _ => ConnectionContext.noEncryption()
      }

      val settings = createClientConnectionSettings(logger)
      val connectionFlow: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
        Http()
          .outgoingConnectionUsingContext(host, port, connectionContext, settings)

      // A new stream is materialized, creating a new connection for every request. The connection is closed on stream completion (success or failure)
      // There is overhead in doing this, but it is the simplest way to implement a request-timeout.
      def dispatchRequest(request: HttpRequest): Future[HttpResponse] =
        Source
          .single(request)
          .via(connectionFlow)
          .completionTimeout(requestParameters.requestTimeout.asFiniteApproximation)
          .runWith(Sink.head)
          .recoverWith { case NonFatal(e) =>
            timing.stop()(HttpClientMetrics.FailureStatus)
            metrics.decInFlight()
            logger.debug(msg("HTTP request failed"), e)(traceContext)
            Future.failed(e)
          }
      val start = System.currentTimeMillis()
      dispatchRequest(request).map { response =>
        metrics.decInFlight()
        timing.stop()(HttpClientMetrics.completedStatus(response))
        val responseTraceCtx = traceContextFromHeaders(response.headers)
        val end = System.currentTimeMillis()
        logger.debug(
          msg(
            s"HTTP request took ${end - start} ms to complete. Received response with status code: ${response.status}"
          )
        )(
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
  }

  object ProxySettings {
    def readFromSystemProperties(): Option[ProxySettings] = {
      def host(scheme: String) = s"$scheme.proxyHost"
      def port(scheme: String) = s"$scheme.proxyPort"
      def user(scheme: String) = s"$scheme.proxyUser"
      def password(scheme: String) = s"$scheme.proxyPassword"
      def prop(property: String): Option[String] =
        Option(System.getProperty(property)).map(_.trim)
      def props(scheme: String) =
        (prop(host(scheme)), prop(port(scheme)), prop(user(scheme)), prop(password(scheme)))
      def mkSettings(scheme: String) = props(scheme) match {
        case (Some(host), Some(port), Some(user), Some(password)) =>
          Some(
            ProxySettings(
              host,
              port.toInt,
              Some(BasicHttpCredentials(user, password)),
            )
          )
        case (Some(host), Some(port), _, _) =>
          Some(ProxySettings(host, port.toInt))
        case _ => None
      }

      mkSettings("http").orElse(mkSettings("https"))
    }
  }

  case class ProxySettings(host: String, port: Int, creds: Option[BasicHttpCredentials] = None) {
    val address: InetSocketAddress = InetSocketAddress.createUnresolved(host, port)
  }

  def apply(
      outerRequestParameters: HttpClient.HttpRequestParameters,
      httpClientMetrics: HttpClientMetrics,
      logger: TracedLogger,
  )(implicit
      ac: ActorSystem,
      ec: ExecutionContext,
  ): HttpClient =
    HttpClient(ApiLoggingConfig(), outerRequestParameters, httpClientMetrics, logger)

  def apply(
      apiLoggingConfig: ApiLoggingConfig,
      outerRequestParameters: HttpClient.HttpRequestParameters,
      httpClientMetrics: HttpClientMetrics,
      logger: TracedLogger,
  )(implicit
      ac: ActorSystem,
      ec: ExecutionContext,
  ): HttpClient =
    new HttpClientImpl(apiLoggingConfig, outerRequestParameters, httpClientMetrics, logger)

  private def traceContextFromHeaders(headers: immutable.Seq[HttpHeader]) = {
    W3CTraceContext
      .fromHeaders(headers.map(h => h.name() -> h.value()).toMap)
      .map(_.toTraceContext)
      .getOrElse(TraceContext.empty)
  }

  private def createClientConnectionSettings(
      logger: TracedLogger
  )(implicit ac: ActorSystem, tc: TraceContext) = {
    // if pekko config is set, it overrides what is set in system properties
    // pekko does not support credentials in `pekko.http.client.proxy`,
    // so that can't be supported here, if users need this they need to set proxy settings via system properties.
    Try(HttpsProxySettings(ac.settings.config))
      .map { proxyConf =>
        logger.debug(
          s"Configuring pekko-http client from pekko.http.client.proxy config: host = ${proxyConf.host}, port = ${proxyConf.port}"
        )
        ClientConnectionSettings(ac).withTransport(
          ClientTransport.httpsProxy()
        )
      }
      .getOrElse {
        def msgPrefix(proxySettings: ProxySettings) =
          s"Configuring pekko-http client from system properties, host = ${proxySettings.host}, port = ${proxySettings.port}"
        ProxySettings
          .readFromSystemProperties()
          .fold {
            logger.debug(
              s"Not using a http client proxy for pekko-http client. No proxy settings found in system properties."
            )
            ClientConnectionSettings(ac)
          } { proxySettings =>
            proxySettings.creds.fold {
              logger.debug(s"${msgPrefix(proxySettings)}, credentials = [redacted]")
              ClientConnectionSettings(ac).withTransport(
                ClientTransport.httpsProxy(proxySettings.address)
              )
            } { creds =>
              logger.debug(s"${msgPrefix(proxySettings)}, no credentials set")
              ClientConnectionSettings(ac).withTransport(
                ClientTransport.httpsProxy(proxySettings.address, creds)
              )
            }
          }
      }
  }
}

object HttpClientMetrics {
  def context(clientName: String, operationName: String, host: String) =
    MetricsContext("clientName" -> clientName, "operationName" -> operationName, "target_host" -> host)
  val FailureStatus = MetricsContext("status" -> "failure")
  def completedStatus(response: HttpResponse) = MetricsContext(
    "status_code" -> response.status.intValue().toString,
    "status" -> "completed",
  )
  def apply(metricsFactory: LabeledMetricsFactory): HttpClientMetrics =
    new HttpClientMetrics(metricsFactory)
}

class HttpClientMetrics(metricsFactory: LabeledMetricsFactory) {
  val prefix: MetricName = MetricName.Daml :+ "http" :+ "client"

  val requestTiming = metricsFactory.timer(
    MetricInfo(
      name = prefix :+ "requests" :+ "duration",
      summary = "Histogram for http client request durations",
      qualification = Latency,
      labelsWithDescription = Map(
        "method" -> "HTTP method of the request",
        "path" -> "path of the HTTP request, limited in length",
        "status" -> "Status of the HTTP request: completed, failure",
      ),
    )
  )
  val inFlightRequests = metricsFactory.counter(
    MetricInfo(
      name = prefix :+ "requests" :+ "inflight",
      summary = "Histogram for http client request durations",
      qualification = Latency,
      labelsWithDescription = Map(
        "method" -> "HTTP method of the request",
        "path" -> "path of the HTTP request, limited in length",
      ),
    )
  )

  def startTiming()(implicit mc: MetricsContext): MetricHandle.Timer.TimerHandle =
    requestTiming.startAsync()
  def incInFlight()(implicit mc: MetricsContext): Unit = inFlightRequests.inc()
  def decInFlight()(implicit mc: MetricsContext): Unit = inFlightRequests.dec()
}
