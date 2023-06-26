package com.daml.network.admin.http

import com.daml.network.environment.{BuildInfo, CNNodeStatus}
import com.daml.network.http.v0.{definitions, commonAdmin as v0}
import com.digitalasset.canton.health.admin.data
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import scala.concurrent.{ExecutionContext, Future}

class HttpAdminHandler(
    status: => Future[data.NodeStatus[CNNodeStatus]],
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.CommonAdminHandler[Unit]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName

  def getHealthStatus(
      respond: v0.CommonAdminResource.GetHealthStatusResponse.type
  )()(extracted: Unit): Future[v0.CommonAdminResource.GetHealthStatusResponse] =
    withNewTrace(workflowId) { _ => _ =>
      status
        .map(s => respond.OK(CNNodeStatus.toJsonNodeStatus(s)))
    }

  override def getVersion(
      respond: v0.CommonAdminResource.GetVersionResponse.type
  )()(extracted: Unit): Future[v0.CommonAdminResource.GetVersionResponse] =
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

  override def isLive(
      respond: v0.CommonAdminResource.IsLiveResponse.type
  )()(extracted: Unit): Future[v0.CommonAdminResource.IsLiveResponse] = {
    withNewTrace(workflowId) { _ => _ =>
      Future(respond.OK)
    }
  }

  override def isReady(
      respond: v0.CommonAdminResource.IsReadyResponse.type
  )()(extracted: Unit): Future[v0.CommonAdminResource.IsReadyResponse] = {
    withNewTrace(workflowId) { _ => _ =>
      status.map { s =>
        if (s.isActive.exists(identity)) respond.OK
        else respond.ServiceUnavailable
      }
    }
  }
}
