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
import org.apache.pekko.http.scaladsl.model.HttpHeader
import org.lfdecentralizedtrust.splice.admin.api.client.HttpCtlRunner
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpCommand, HttpCommandException}
import org.lfdecentralizedtrust.splice.config.NetworkAppClientConfig
import org.lfdecentralizedtrust.splice.environment.SpliceEnvironment
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import scala.concurrent.{ExecutionContextExecutor, TimeoutException}

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
      command: HttpCommand[?, Result],
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

      implicit val httpClient: HttpClient =
        HttpClient(HttpClient.HttpRequestParameters(requestTimeout), logger)

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
