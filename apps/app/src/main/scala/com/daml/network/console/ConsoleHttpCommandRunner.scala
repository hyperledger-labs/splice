// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.console

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.Materializer
import com.daml.network.admin.api.client.HttpCtlRunner
import com.daml.network.admin.api.client.commands.HttpCommand
import com.daml.network.config.CNHttpClientConfig
import com.daml.network.environment.CNNodeEnvironment
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand.{
  CustomClientTimeout,
  DefaultBoundedTimeout,
  DefaultUnboundedTimeout,
  ServerEnforcedTimeout,
}
import com.digitalasset.canton.config.{
  ConsoleCommandTimeout,
  NonNegativeDuration,
  ProcessingTimeout,
}
import com.digitalasset.canton.console.{
  CommandErrors,
  ConsoleCommandResult,
  StringErrorEitherToCommandResultExtensions,
}
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContextExecutor, Future, TimeoutException}

/** HTTP version of Canton’s GrpcAdminCommandRunner
  */
class ConsoleHttpCommandRunner(
    environment: CNNodeEnvironment,
    timeouts: ProcessingTimeout,
    commandTimeouts: ConsoleCommandTimeout,
)(implicit tracer: Tracer, templateDecoder: TemplateJsonDecoder)
    extends NamedLogging
    with AutoCloseable
    with Spanning {

  private implicit val executionContext: ExecutionContextExecutor =
    environment.executionContext
  override val loggerFactory: NamedLoggerFactory = environment.loggerFactory

  private val httpRunner = new HttpCtlRunner(
    loggerFactory
  )
  implicit val actorSystem = ActorSystem("ConsoleHttpCommandRunner", environment.config.akkaConfig)
  implicit val mat: Materializer = Materializer(actorSystem)

  val httpExt = Http()(actorSystem)

  implicit val httpClient: HttpRequest => Future[HttpResponse] = (req: HttpRequest) =>
    httpExt.singleRequest(
      req,
      settings = ConnectionPoolSettings(actorSystem),
    )

  def runCommand[Result](
      instanceName: String,
      command: HttpCommand[_, Result],
      headers: List[HttpHeader],
      clientConfig: CNHttpClientConfig,
  ): ConsoleCommandResult[Result] =
    withNewTrace[ConsoleCommandResult[Result]](command.fullName) { implicit traceContext => span =>
      span.setAttribute("instance_name", instanceName)
      val awaitTimeout = command.timeoutType match {
        case CustomClientTimeout(timeout) => timeout
        case ServerEnforcedTimeout => NonNegativeDuration(Duration.Inf)
        case DefaultBoundedTimeout => commandTimeouts.bounded
        case DefaultUnboundedTimeout => commandTimeouts.unbounded
      }
      logger.debug(s"Running on ${instanceName} command ${command} against ${clientConfig}")(
        traceContext
      )
      // TODO(#2019): after all apps are HTTP-only, construct host from clientConfig.address + clientConfig.port. Then we can drop CNHttpClientConfig.
      val host = clientConfig.url
      try {
        val apiResult =
          awaitTimeout.await(
            s"Running on ${instanceName} command ${command} against ${clientConfig}"
          )(
            httpRunner
              .run(host, command, headers)
              .value
          )
        apiResult.toResult
      } catch {
        case _: TimeoutException =>
          CommandErrors.ConsoleTimeout.Error(awaitTimeout.asJavaApproximation)
      }
    }

  override def close(): Unit =
    Lifecycle.close(Lifecycle.toCloseableActorSystem(actorSystem, logger, timeouts))(logger)
}
