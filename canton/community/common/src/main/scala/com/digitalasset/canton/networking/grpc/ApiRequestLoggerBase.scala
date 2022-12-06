// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.networking.grpc

import com.digitalasset.canton.config.ApiLoggingConfig
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.tracing.{SerializableTraceContext, TraceContext, TraceContextGrpc}
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status.Code.*
import io.grpc.*

import scala.util.Try

/** Server side interceptor that logs incoming and outgoing traffic.
  *
  * @param config Configuration to tailor the output
  */
@SuppressWarnings(Array("org.wartremover.warts.Null"))
class ApiRequestLoggerBase(
    override protected val loggerFactory: NamedLoggerFactory,
    config: ApiLoggingConfig,
) extends NamedLogging {

  private lazy val printer = config.printer

  protected def logThrowable(
      within: => Unit
  )(createLogMessage: String => String, traceContext: TraceContext): Unit = {
    try {
      within
    } catch {
      // If the server implementation fails, the server method must return a failed future or call StreamObserver.onError.
      // This handler is invoked, when an internal GRPC error occurs or the server implementation throws.
      case t: Throwable =>
        logger.error(createLogMessage("failed with an unexpected throwable"), t)(traceContext)
        t match {
          case _: RuntimeException =>
            throw t
          case _: Exception =>
            // Convert to a RuntimeException, because GRPC is unable to handle undeclared checked exceptions.
            throw new RuntimeException(t)
          case _: Throwable =>
            throw t
        }
    }
  }

  protected def logStatusOnClose(
      status: Status,
      trailers: Metadata,
      createLogMessage: String => String,
  )(implicit requestTraceContext: TraceContext): Status = {
    val enhancedStatus = enhance(status)

    val statusString = Option(enhancedStatus.getDescription).filterNot(_.isEmpty) match {
      case Some(d) => s"${enhancedStatus.getCode}/$d"
      case None => enhancedStatus.getCode.toString
    }

    val trailersString = stringOfTrailers(trailers)

    if (enhancedStatus.getCode == Status.OK.getCode) {
      logger.debug(
        createLogMessage(s"succeeded($statusString)$trailersString"),
        enhancedStatus.getCause,
      )
    } else {
      val message = createLogMessage(s"failed with $statusString$trailersString")
      if (enhancedStatus.getCode == UNKNOWN || enhancedStatus.getCode == DATA_LOSS) {
        logger.error(message, enhancedStatus.getCause)
      } else if (enhancedStatus.getCode == INTERNAL) {
        if (enhancedStatus.getDescription == "Half-closed without a request") {
          // If a call is cancelled, GRPC may half-close the call before the first message has been delivered.
          // The result is this status.
          // Logging with INFO to not confuse the user.
          // The status is still delivered to the client, to facilitate troubleshooting if there is a deeper problem.
          logger.info(message, enhancedStatus.getCause)
        } else {
          logger.error(message, enhancedStatus.getCause)
        }
      } else if (enhancedStatus.getCode == UNAUTHENTICATED) {
        logger.debug(message, enhancedStatus.getCause)
      } else {
        logger.info(message, enhancedStatus.getCause)
      }
    }

    enhancedStatus
  }

  @SuppressWarnings(Array("org.wartremover.warts.Product"))
  protected def cutMessage(message: Any): String =
    if (config.logMessagePayloads) printer.printAdHoc(message)
    else ""

  protected def stringOfTrailers(trailers: Metadata): String =
    if (!config.logMessagePayloads || trailers == null || trailers.keys().isEmpty) {
      ""
    } else {
      s"\n  Trailers: ${stringOfMetadata(trailers)}"
    }

  protected def stringOfMetadata(metadata: Metadata): String =
    if (!config.logMessagePayloads || metadata == null) {
      ""
    } else {
      metadata.toString.limit(config.maxMetadataSize).toString
    }

  protected def enhance(status: Status): Status = {
    if (status.getDescription == null && status.getCause != null) {
      // Copy the exception message to the status in order to transmit it to the client.
      // If you consider this a security risk:
      // - Exceptions are logged. Therefore, exception messages must not contain confidential data anyway.
      // - Note that scalapb.grpc.Grpc.completeObserver also copies exception messages into the status description.
      //   So removing this method would not mitigate the risk.
      status.withDescription(status.getCause.getLocalizedMessage)
    } else {
      status
    }
  }

  protected def inferRequestTraceContext: TraceContext = {
    val grpcTraceContext = TraceContextGrpc.fromGrpcContext
    if (grpcTraceContext.traceId.isDefined) {
      grpcTraceContext
    } else {
      TraceContext.withNewTraceContext(identity)
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.AsInstanceOf"))
  protected def traceContextOfMessage[A](message: Any): Option[TraceContext] = {
    import scala.language.reflectiveCalls
    for {
      maybeTraceContextP <- Try(
        message
          .asInstanceOf[{ def traceContext: Option[com.digitalasset.canton.v0.TraceContext] }]
          .traceContext
      ).toOption
      tc <- ProtoConverter.required("traceContextOfMessage", maybeTraceContextP).toOption
      traceContext <- SerializableTraceContext.fromProtoV0(tc).toOption
    } yield traceContext.unwrap
  }
}
