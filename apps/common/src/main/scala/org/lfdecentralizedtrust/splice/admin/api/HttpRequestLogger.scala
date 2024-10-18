// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.api

import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, RemoteAddress}
import org.apache.pekko.http.scaladsl.server.{
  AuthorizationFailedRejection,
  Directive0,
  RequestContext,
}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.RouteResult.{Complete, Rejected}
import com.digitalasset.canton.config.ApiLoggingConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*

object HttpRequestLogger {
  def apply(
      messagePayloads: Boolean,
      maxPathLength: Int,
      maxStringLength: Int,
      maxMetadataSize: Int,
      loggerFactory: NamedLoggerFactory,
  )(implicit traceContext: TraceContext): Directive0 = {
    new HttpRequestLogger(
      messagePayloads,
      maxPathLength,
      maxStringLength,
      maxMetadataSize,
      loggerFactory,
    ).directive
  }

  // ignores maxMethodLength and maxMessageLines
  def apply(
      loggingConfig: ApiLoggingConfig,
      loggerFactory: NamedLoggerFactory,
  )(implicit traceContext: TraceContext): Directive0 = apply(
    messagePayloads = loggingConfig.messagePayloads,
    maxPathLength = loggingConfig.maxMethodLength,
    maxStringLength = loggingConfig.maxStringLength,
    maxMetadataSize = loggingConfig.maxMetadataSize,
    loggerFactory = loggerFactory,
  )
}

final class HttpRequestLogger(
    messagePayloads: Boolean,
    maxPathLength: Int,
    maxStringLength: Int,
    maxMetadataSize: Int,
    override protected val loggerFactory: NamedLoggerFactory,
) extends NamedLogging {
  def createLogMessage(ctx: RequestContext, remoteAddress: RemoteAddress)(
      message: String
  ): String = {
    val pathLimited = ctx.request.uri.path.toString
      .limit(maxPathLength)
    s"HTTP ${ctx.request.method.name} ${pathLimited} from (${remoteAddress}): ${message}"
  }

  private def directive(implicit traceContext: TraceContext): Directive0 = {
    extractClientIP.flatMap { remoteAddress =>
      extractRequestContext.flatMap { ctx =>
        val msg = createLogMessage(ctx, remoteAddress)
        logger.debug(msg("received request."))

        if (messagePayloads) {
          ctx.request.entity match {
            // Only logging strict messages which are already in memory, not attempting to log streams
            case HttpEntity.Strict(ContentTypes.`application/json`, data) =>
              logger.debug(
                msg(s"Received entity data: ${data.utf8String.limit(maxStringLength)}")
              )
            case _ => logger.debug(msg(s"omitting logging of request entity data."))
          }
        }
        ctx.request.uri.rawQueryString.foreach { _ =>
          logger.debug(msg(s"query string: ${ctx.request.uri.queryString()}"))
        }
        logger.trace(msg(s"headers: ${ctx.request.headers.toString.limit(maxMetadataSize)}"))
        mapRouteResult { result =>
          result match {
            case Complete(response) =>
              logger.debug(msg(s"Responding with status code: ${response.status}"))
              if (messagePayloads) {
                response.entity match {
                  // Only logging strict messages which are already in memory, not attempting to log streams
                  case HttpEntity.Strict(ContentTypes.`application/json`, data) =>
                    logger.debug(
                      msg(
                        s"Responding with entity data: ${data.utf8String.limit(maxStringLength)}"
                      )
                    )
                  case _ => logger.debug(msg(s"omitting logging of response entity data."))
                }
              }
              logger.trace(
                msg(
                  s"Responding with headers: ${response.headers.toString.limit(maxMetadataSize)}"
                )
              )

            case Rejected(rejections) =>
              if (rejections.contains(AuthorizationFailedRejection)) {
                logger.debug(msg("Rejected: Unauthorized."))
              } else {
                logger.debug(msg(s"""Rejected: ${rejections.mkString(",")}"""))
              }
          }
          result
        }
      }
    }
  }
}
