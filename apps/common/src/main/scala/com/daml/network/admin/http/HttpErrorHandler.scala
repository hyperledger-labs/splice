package com.daml.network.admin.http

import akka.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpResponse,
  StatusCode,
  StatusCodes,
  MediaTypes,
}
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.server.{Directive0, ExceptionHandler, StandardRoute}
import akka.http.scaladsl.server.Directives.withRequestTimeoutResponse
import akka.http.scaladsl.server.Directives.{complete, extractUri, handleExceptions}
import akka.util.ByteString
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import io.circe.syntax.*
import io.circe.Printer
import com.daml.network.http.v0.definitions as d0
import scala.util.{Try, Success, Failure}

case class CustomHttpError(code: Status.Code, message: String) extends Exception

object HttpErrorHandler {
  def apply(loggerFactory: NamedLoggerFactory)(implicit traceContext: TraceContext): Directive0 =
    new HttpErrorHandler(
      loggerFactory
    ).directive

  def onGrpcNotFound[T](message: String): Try[T] => Try[T] =
    (t: Try[T]) => {
      t match {
        case Success(value) => Success(value)
        case Failure(exception) => {
          exception match {
            case e: StatusRuntimeException
                if e.getStatus.getCode == io.grpc.Status.Code.NOT_FOUND =>
              Failure(new CustomHttpError(e.getStatus.getCode, message))
            case x => Failure(x)
          }
        }
      }
    }
}

final class HttpErrorHandler(
    override val loggerFactory: NamedLoggerFactory
) extends NamedLogging {

  private def mapToStatusCode(grpcCode: Status.Code): StatusCode = {
    grpcCode match {
      case Status.Code.NOT_FOUND => StatusCodes.NotFound
      case Status.Code.ABORTED => StatusCodes.TooManyRequests
      case Status.Code.UNAVAILABLE => StatusCodes.ServiceUnavailable
      case Status.Code.INTERNAL => StatusCodes.InternalServerError
      case Status.Code.FAILED_PRECONDITION => StatusCodes.BadRequest
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

  private def completeErrorResponse(grpcCode: Status.Code, message: String): StandardRoute =
    completeErrorResponse(mapToStatusCode(grpcCode), message)

  def directive(implicit traceContext: TraceContext) = exceptionsDirective & timeoutDirective

  def exceptionsDirective(implicit traceContext: TraceContext) = {
    val handler = ExceptionHandler {
      case e: CustomHttpError =>
        extractUri { uri =>
          logger.info(
            s"Request to $uri resulted in an HTTP exception: ${e.message}",
            e,
          )
          completeErrorResponse(e.code, e.message)
        }
      case e: StatusRuntimeException =>
        extractUri { uri =>
          logger.info(
            s"Request to $uri resulted in a gRPC StatusRuntimeException: ${e.getMessage}",
            e,
          )
          completeErrorResponse(e.getStatus.getCode, e.getStatus.getDescription)
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

  def timeoutDirective: Directive0 = {
    withRequestTimeoutResponse(request => {
      val contentType = MediaTypes.`application/json`
      val errorResponse =
        d0.ErrorResponse(
          s"The server is taking too long to respond to the request at ${request.uri}"
        )
      val responseEntity = HttpEntity(
        contentType = contentType,
        ByteString(
          Printer.noSpaces.printToByteBuffer(errorResponse.asJson, contentType.charset.nioCharset())
        ),
      )
      HttpResponse(
        StatusCodes.ServiceUnavailable,
        entity = responseEntity,
      )
    })
  }
}
