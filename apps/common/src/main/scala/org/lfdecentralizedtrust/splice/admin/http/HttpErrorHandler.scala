// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.http

import org.apache.pekko.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpRequest,
  HttpResponse,
  MediaTypes,
  StatusCode,
  StatusCodes,
}
import org.apache.pekko.http.scaladsl.model.StatusCodes.InternalServerError
import org.apache.pekko.http.scaladsl.server.{Directive0, ExceptionHandler, StandardRoute}
import org.apache.pekko.http.scaladsl.server.Directives.{
  complete,
  extractRequestTimeout,
  extractUri,
  handleExceptions,
  withRequestTimeoutResponse,
}
import org.apache.pekko.util.ByteString
import com.digitalasset.canton.ledger.error.groups.CommandExecutionErrors.Interpreter
import org.lfdecentralizedtrust.splice.http.v0.definitions as d0
import com.digitalasset.canton.error.ErrorCodeUtils
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import io.circe.Printer
import io.circe.syntax.*
import io.grpc.{Status, StatusRuntimeException}

import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

final case class HttpErrorWithGrpcStatus(status: Status, message: String)
    extends Exception(s"HTTP request failed with status $status: $message")
final case class HttpErrorWithHttpCode(code: StatusCode, message: String)
    extends Exception(s"HTTP request failed with status code $code: $message")

object HttpErrorHandler {
  def apply(loggerFactory: NamedLoggerFactory)(implicit traceContext: TraceContext): Directive0 =
    new HttpErrorHandler(
      loggerFactory
    ).directive

  // 400
  def badRequest(message: String) = HttpErrorWithHttpCode(StatusCodes.BadRequest, message);

  // 401
  def unauthorized(message: String) = HttpErrorWithHttpCode(StatusCodes.Unauthorized, message);

  // 409
  def conflict(message: String) = HttpErrorWithHttpCode(StatusCodes.Conflict, message);

  // 404
  def notFound(message: String) = HttpErrorWithHttpCode(StatusCodes.NotFound, message);

  // 500
  def internalServerError(message: String) =
    HttpErrorWithHttpCode(StatusCodes.InternalServerError, message);

  // 501
  def notImplemented(message: String) =
    HttpErrorWithHttpCode(StatusCodes.NotImplemented, message);

  // 503
  def serviceUnavailable(message: String) =
    HttpErrorWithHttpCode(StatusCodes.ServiceUnavailable, message);

  private def grpcErrorCatcher[T](
      grpcCondition: (Status.Code => Boolean),
      message: String,
  ): Try[T] => Try[T] =
    (t: Try[T]) => {
      t match {
        case Success(value) => Success(value)
        case Failure(exception) => {
          exception match {
            case e: StatusRuntimeException if grpcCondition(e.getStatus.getCode) =>
              Failure(new HttpErrorWithGrpcStatus(e.getStatus, message))
            case x => Failure(x)
          }
        }
      }
    }

  def onGrpcNotFound[T](message: String): Try[T] => Try[T] =
    grpcErrorCatcher(_ == io.grpc.Status.Code.NOT_FOUND, message)

  def onGrpcAlreadyExists[T](message: String): Try[T] => Try[T] =
    grpcErrorCatcher(_ == io.grpc.Status.Code.ALREADY_EXISTS, message)
}

final class HttpErrorHandler(
    override val loggerFactory: NamedLoggerFactory
) extends NamedLogging {

  private def mapToStatusCode(grpcStatus: Status): StatusCode = {
    val grpcCode = grpcStatus.getCode
    grpcCode match {
      case Status.Code.NOT_FOUND => StatusCodes.NotFound
      case Status.Code.ALREADY_EXISTS => StatusCodes.Conflict
      case Status.Code.ABORTED => StatusCodes.TooManyRequests
      case Status.Code.UNAVAILABLE => StatusCodes.ServiceUnavailable
      case Status.Code.INTERNAL => StatusCodes.InternalServerError
      case Status.Code.FAILED_PRECONDITION =>
        val grpcDesc = grpcStatus.getDescription
        val conflictErrorCodes = Seq(
          Interpreter.GenericInterpretationError,
          Interpreter.UnhandledException,
          Interpreter.InterpretationUserError,
          Interpreter.TemplatePreconditionViolated,
        )
        if (conflictErrorCodes.exists(ErrorCodeUtils.isError(grpcDesc, _)))
          StatusCodes.Conflict
        else
          StatusCodes.BadRequest
      case Status.Code.INVALID_ARGUMENT => StatusCodes.BadRequest
      case Status.Code.UNIMPLEMENTED => StatusCodes.BadRequest
      case _ => StatusCodes.InternalServerError
    }
  }

  private def completeErrorResponse(httpCode: StatusCode, message: String): StandardRoute =
    complete(
      HttpResponse(
        httpCode,
        entity = HttpEntity(
          ContentTypes.`application/json`,
          d0.ErrorResponse
            .encodeErrorResponse(d0.ErrorResponse(message))
            .toString,
        ),
      )
    )

  private def completeErrorResponse(grpcStatus: Status, message: String): StandardRoute =
    completeErrorResponse(mapToStatusCode(grpcStatus), message)

  def directive(implicit traceContext: TraceContext) = exceptionsDirective & timeoutDirective

  def exceptionsDirective(implicit traceContext: TraceContext) = {
    val handler = ExceptionHandler {
      case HttpErrorWithGrpcStatus(code, message) =>
        extractUri { uri =>
          logger.info(s"Request to $uri resulted in an HTTP exception: ${message}")
          completeErrorResponse(code, message)
        }
      case HttpErrorWithHttpCode(code, message) =>
        extractUri { uri =>
          logger.info(s"Request to $uri resulted in an HTTP exception: ${message}")
          completeErrorResponse(code, message)
        }
      case e: StatusRuntimeException =>
        extractUri { uri =>
          logger.info(
            s"Request to $uri resulted in a gRPC StatusRuntimeException: ${e.getMessage}",
            e,
          )
          completeErrorResponse(e.getStatus, e.getStatus.getDescription)
        }
      case e: IllegalArgumentException =>
        extractUri { uri =>
          logger.info(s"Request to $uri was malformed: ${e.getMessage}", e)
          completeErrorResponse(
            StatusCodes.BadRequest,
            e.getMessage,
          )
        }
      case e: Throwable =>
        extractUri { uri =>
          logger.error(s"Request to $uri resulted in an unexpected exception: ${e.getMessage}", e)
          completeErrorResponse(
            InternalServerError,
            "An unexpected error occurred.",
          )
        }
    }
    handleExceptions(handler)
  }

  def timeoutDirective(implicit traceContext: TraceContext): Directive0 = {
    extractRequestTimeout.flatMap { timeout =>
      withRequestTimeoutResponse(request => {
        timeoutHandler(timeout, request)
      })
    }
  }

  def timeoutHandler(timeout: Duration, request: HttpRequest)(implicit tc: TraceContext) = {
    logger.warn(s"Request to ${request.uri} resulted in a timeout after $timeout.")
    val contentType = MediaTypes.`application/json`
    val errorResponse =
      d0.ErrorResponse(
        s"The server is taking too long to respond to the request at ${request.uri}"
      )
    val responseEntity = HttpEntity(
      contentType = contentType,
      ByteString(
        Printer.noSpaces
          .printToByteBuffer(errorResponse.asJson, contentType.charset.nioCharset())
      ),
    )
    HttpResponse(
      StatusCodes.ServiceUnavailable,
      entity = responseEntity,
    )
  }
}
