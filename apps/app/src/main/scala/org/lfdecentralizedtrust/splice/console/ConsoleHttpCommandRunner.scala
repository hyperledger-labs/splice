// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.console

import com.digitalasset.canton.config.{ConsoleCommandTimeout, NonNegativeDuration}
import com.digitalasset.canton.console.{
  CommandErrors,
  ConsoleCommandResult,
  StringErrorEitherToCommandResultExtensions,
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import org.apache.pekko.http.scaladsl.{ConnectionContext, Http}
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.lfdecentralizedtrust.splice.admin.api.client.HttpCtlRunner
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpCommand, HttpCommandException}
import org.lfdecentralizedtrust.splice.config.NetworkAppClientConfig
import org.lfdecentralizedtrust.splice.environment.SpliceEnvironment
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import javax.net.ssl.SSLContext
import scala.concurrent.{ExecutionContextExecutor, Future, TimeoutException}
import scala.util.control.NonFatal

/** HTTP version of Canton’s GrpcAdminCommandRunner
  */
class ConsoleHttpCommandRunner(
    environment: SpliceEnvironment,
    commandTimeouts: ConsoleCommandTimeout,
    requestTimeout: NonNegativeDuration,
)(implicit tracer: Tracer, templateDecoder: TemplateJsonDecoder)
    extends NamedLogging
    with Spanning {

  private implicit val executionContext: ExecutionContextExecutor =
    environment.executionContext
  private implicit val actorSystem: ActorSystem = environment.actorSystem
  override val loggerFactory: NamedLoggerFactory = environment.loggerFactory

  private val httpRunner = new HttpCtlRunner(
    loggerFactory
  )

  def runCommand[Result](
      instanceName: String,
      command: HttpCommand[_, Result],
      headers: List[HttpHeader],
      clientConfig: NetworkAppClientConfig,
  ): ConsoleCommandResult[Result] =
    withNewTrace[ConsoleCommandResult[Result]](command.fullName) { implicit traceContext => span =>
      span.setAttribute("instance_name", instanceName)
      val commandDescription =
        s"Running on $instanceName command $command against $clientConfig"
      logger.debug(commandDescription)(
        traceContext
      )
      val commandTimeout = commandTimeouts.bounded

      def buildHttpClient(
          outerRequestParameters: HttpClient.HttpRequestParameters
      ): HttpClient = new HttpClient {
        override val requestParameters: HttpClient.HttpRequestParameters = outerRequestParameters

        override def withOverrideParameters(
            newParameters: HttpClient.HttpRequestParameters
        ): HttpClient = buildHttpClient(newParameters)

        override def executeRequest(request: HttpRequest): Future[HttpResponse] = {
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
              .completionTimeout(requestParameters.requestTimeout.asFiniteApproximation)
              .runWith(Sink.head)
              .recoverWith { case NonFatal(e) =>
                logger.debug(s"$commandDescription, HTTP request failed", e)(traceContext)
                Future.failed(e)
              }

          dispatchRequest(request)
        }
      }

      implicit val httpClient: HttpClient =
        buildHttpClient(HttpClient.HttpRequestParameters(requestTimeout))

      val url = clientConfig.url
      try {
        val start = System.currentTimeMillis()
        val apiResult =
          commandTimeout.await(commandDescription)(
            httpRunner.run(url.toString(), command, headers).value
          )
        val end = System.currentTimeMillis()
        logger.trace(s"$commandDescription, HTTP request took ${end - start} ms to complete")
        apiResult.toResult
      } catch {
        case httpErr: HttpCommandException =>
          CommandErrors.GenericCommandError(httpErr.toString())
        case _: TimeoutException =>
          logger.debug(
            s"$commandDescription, HTTP request timed out on commandTimeout: ${commandTimeout} "
          )(
            traceContext
          )
          CommandErrors.ConsoleTimeout.Error(commandTimeout.asJavaApproximation)
      }
    }

}
