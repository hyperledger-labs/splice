package com.daml.network.admin.http

import com.daml.network.http.v0.{definitions, commonAdmin as v0}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer
import com.digitalasset.canton.health.admin.{data}

import scala.concurrent.{ExecutionContext, Future}

import com.daml.network.environment.BuildInfo
import java.time.{OffsetDateTime, Instant, ZoneOffset}

class HttpAdminHandler[S <: data.NodeStatus.Status](
    status: => Future[data.NodeStatus[S]],
    serialize: S => definitions.Status,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.CommonAdminHandler
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName

  def getHealthStatus(
      respond: v0.CommonAdminResource.GetHealthStatusResponse.type
  )(): Future[v0.CommonAdminResource.GetHealthStatusResponse] = withNewTrace(workflowId) { _ => _ =>
    status
      .map {
        case data.NodeStatus.Success(status) =>
          definitions.NodeStatus(success = Some(serialize(status)))
        case data.NodeStatus.NotInitialized(active) =>
          definitions.NodeStatus(
            notInitialized = Some(
              definitions.NotInitialized(active)
            )
          )
        case data.NodeStatus.Failure(_) =>
          definitions.NodeStatus(None, None)
      }
      .map(respond.OK(_))
  }

  override def getVersion(
      respond: v0.CommonAdminResource.GetVersionResponse.type
  )(): Future[v0.CommonAdminResource.GetVersionResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future(
        respond.OK(
          definitions.Version(
            version = BuildInfo.compiledVersion,
            commitTs = OffsetDateTime.ofInstant(
              Instant.ofEpochSecond(BuildInfo.commitUnixTimestamp.toLong),
              ZoneOffset.UTC,
            ),
          )
        )
      )
    }
}
