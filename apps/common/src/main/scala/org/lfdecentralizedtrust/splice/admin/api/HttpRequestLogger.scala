// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.api

import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, RemoteAddress}
import org.apache.pekko.http.scaladsl.server.{
  Directive0,
  RejectionHandler,
  RequestContext,
}
import org.apache.pekko.http.scaladsl.server.Directives.*
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

  /** Creates a RejectionHandler that logs the rejection status code, then delegates to the
    * default handler to produce the appropriate HTTP response. This should be used at the
    * top level of the route tree (via handleRejections) to seal the route — ensuring all
    * rejections become Complete responses with their reason logged.
    *
    * Must be placed OUTSIDE the HttpRequestLogger directive so that the logger's
    * mapResponse sees the rejection-converted responses too.
    */
  def loggingRejectionHandler(
      loggingConfig: ApiLoggingConfig,
      loggerFactory: NamedLoggerFactory,
  )(implicit traceContext: TraceContext): RejectionHandler = {
    val inst = new HttpRequestLogger(
      loggingConfig.messagePayloads,
      loggingConfig.maxMethodLength,
      loggingConfig.maxStringLength,
      loggingConfig.maxMetadataSize,
      loggerFactory,
    )
    RejectionHandler.default.mapRejectionResponse { response =>
      if (response.status.isFailure()) {
        inst.logger.debug(s"HTTP request rejected with status code: ${response.status}")
      }
      response
    }
  }
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
        mapResponse { response =>
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
          response
        }
      }
    }
  }
}
