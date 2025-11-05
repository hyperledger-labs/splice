// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.http

import com.digitalasset.canton.config.{ApiLoggingConfig, NonNegativeDuration}
import com.digitalasset.canton.logging.TracedLogger
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
import org.apache.pekko.http.scaladsl.settings.ClientConnectionSettings

import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import scala.collection.immutable
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.control.NonFatal

trait HttpClient {

  val requestParameters: HttpClient.HttpRequestParameters

  def withOverrideParameters(newParameters: HttpClient.HttpRequestParameters): HttpClient

  def executeRequest(request: HttpRequest): Future[HttpResponse]

}

object HttpClient {
  case class HttpRequestParameters(requestTimeout: NonNegativeDuration)

  object ProxySettings {
    def readFromEnvVars(): Option[ProxySettings] = {
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
              InetSocketAddress.createUnresolved(host, port.toInt),
              Some(BasicHttpCredentials(user, password)),
            )
          )
        case (Some(host), Some(port), None, None) =>
          Some(ProxySettings(InetSocketAddress.createUnresolved(host, port.toInt)))
        case _ => None
      }

      mkSettings("http").orElse(mkSettings("https"))
    }
  }

  case class ProxySettings(address: InetSocketAddress, creds: Option[BasicHttpCredentials] = None)

  def apply(outerRequestParameters: HttpClient.HttpRequestParameters, logger: TracedLogger)(implicit
      ac: ActorSystem,
      ec: ExecutionContextExecutor,
  ): HttpClient =
    HttpClient(ApiLoggingConfig(), outerRequestParameters, logger)

  def apply(
      apiLoggingConfig: ApiLoggingConfig,
      outerRequestParameters: HttpClient.HttpRequestParameters,
      logger: TracedLogger,
  )(implicit
      ac: ActorSystem,
      ec: ExecutionContextExecutor,
  ): HttpClient =
    new HttpClient {
      override val requestParameters: HttpClient.HttpRequestParameters = outerRequestParameters

      override def withOverrideParameters(
          newParameters: HttpClient.HttpRequestParameters
      ): HttpClient = HttpClient(apiLoggingConfig, newParameters, logger)

      override def executeRequest(request: HttpRequest): Future[HttpResponse] = {
        implicit val traceContext: TraceContext = traceContextFromHeaders(request.headers)
        import apiLoggingConfig.*
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

        val settings = createClientConnectionSettings()
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
              logger.debug(msg("HTTP request failed"), e)(traceContext)
              Future.failed(e)
            }
        val start = System.currentTimeMillis()
        dispatchRequest(request).map { response =>
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

  private def traceContextFromHeaders(headers: immutable.Seq[HttpHeader]) = {
    W3CTraceContext
      .fromHeaders(headers.map(h => h.name() -> h.value()).toMap)
      .map(_.toTraceContext)
      .getOrElse(TraceContext.empty)
  }

  private def createClientConnectionSettings()(implicit ac: ActorSystem) = {

    ProxySettings.readFromEnvVars().fold(ClientConnectionSettings(ac)) { proxySettings =>
      proxySettings.creds.fold(
        ClientConnectionSettings(ac).withTransport(
          ClientTransport.httpsProxy(proxySettings.address)
        )
      ) { creds =>
        ClientConnectionSettings(ac).withTransport(
          ClientTransport.httpsProxy(proxySettings.address, creds)
        )
      }
    }
  }
}
