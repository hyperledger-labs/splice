// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.console

import com.digitalasset.canton.config.ConsoleCommandTimeout
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
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import scala.concurrent.{ExecutionContextExecutor, TimeoutException}

/** HTTP version of Canton’s GrpcAdminCommandRunner
  */
class ConsoleHttpCommandRunner(
    commandTimeouts: ConsoleCommandTimeout,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    tracer: Tracer,
    templateDecoder: TemplateJsonDecoder,
    httpClient: HttpClient,
    ec: ExecutionContextExecutor,
    as: ActorSystem,
) extends NamedLogging
    with Spanning {
  private val httpRunner = new HttpCtlRunner(
    loggerFactory
  )

  def runCommand[Result](
      instanceName: String,
      command: HttpCommand[?, Result],
      headers: List[HttpHeader],
      clientConfig: NetworkAppClientConfig,
  ): ConsoleCommandResult[Result] =
    withNewTrace[ConsoleCommandResult[Result]](command.commandName) {
      implicit traceContext => span =>
        span.setAttribute("instance_name", instanceName)
        val commandDescription =
          s"Running on $instanceName command $command against $clientConfig"
        logger.debug(commandDescription)(
          traceContext
        )
        val commandTimeout = commandTimeouts.bounded

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
