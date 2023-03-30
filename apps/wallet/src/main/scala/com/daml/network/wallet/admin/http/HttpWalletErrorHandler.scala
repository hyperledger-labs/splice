package com.daml.network.wallet.admin.http

import akka.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpResponse,
  StatusCode,
  StatusCodes,
  MediaTypes,
}
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.server.{Directive0, ExceptionHandler}
import akka.http.scaladsl.server.Directives.withRequestTimeoutResponse
import akka.http.scaladsl.server.Directives.{complete, extractUri, handleExceptions}
import akka.util.ByteString
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import io.circe.syntax.*
import io.circe.Printer
import com.daml.network.http.v0.definitions as d0

object HttpWalletErrorHandler {
  def apply(loggerFactory: NamedLoggerFactory)(implicit traceContext: TraceContext): Directive0 =
    new HttpWalletErrorHandler(
      loggerFactory
    ).directive
}
final class HttpWalletErrorHandler(
    override val loggerFactory: NamedLoggerFactory
) extends NamedLogging {

  def mapToStatusCode(grpcCode: Status.Code): StatusCode = {
    grpcCode match {
      case Status.Code.NOT_FOUND => StatusCodes.NotFound
      case Status.Code.ABORTED => StatusCodes.TooManyRequests
      case Status.Code.UNAVAILABLE => StatusCodes.ServiceUnavailable
      case Status.Code.INTERNAL => StatusCodes.InternalServerError
      case Status.Code.FAILED_PRECONDITION => StatusCodes.BadRequest
      case Status.Code.INVALID_ARGUMENT => StatusCodes.BadRequest
      case _ => StatusCodes.InternalServerError
    }
  }

  def directive(implicit traceContext: TraceContext) = exceptionsDirective & timeoutDirective

  def exceptionsDirective(implicit traceContext: TraceContext) = {
    val handler = ExceptionHandler {
      case e: StatusRuntimeException =>
        extractUri { uri =>
          logger.info(
            s"Request to $uri resulted in a gRPC StatusRuntimeException: ${e.getMessage}",
            e,
          )
          complete(
            HttpResponse(
              mapToStatusCode(e.getStatus.getCode),
              entity = HttpEntity(
                ContentTypes.`application/json`,
                d0.ErrorResponse
                  .encodeErrorResponse(d0.ErrorResponse(e.getStatus.getDescription))
                  .toString,
              ),
            )
          )
        }
      case e: Throwable =>
        extractUri { uri =>
          logger.error(s"Request to $uri resulted in an unexpected exception: ${e.getMessage}", e)
          complete(
            HttpResponse(
              InternalServerError,
              entity = HttpEntity(
                ContentTypes.`application/json`,
                d0.ErrorResponse
                  .encodeErrorResponse(d0.ErrorResponse("An unexpected error occurred."))
                  .toString,
              ),
            )
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
