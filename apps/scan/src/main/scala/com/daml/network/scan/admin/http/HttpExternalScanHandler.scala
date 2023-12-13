package com.daml.network.scan.admin.http

import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.environment.SequencerAdminConnection
import com.daml.network.http.v0.{definitions, external}
import com.daml.network.http.v0.external.scan.ScanResource
import com.daml.network.scan.store.ScanStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{DomainId, Member}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class HttpExternalScanHandler(
    store: ScanStore,
    sequencerAdminConnection: SequencerAdminConnection,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends external.scan.ScanHandler[TraceContext]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName

  override def getMemberTrafficStatus(
      respond: ScanResource.GetMemberTrafficStatusResponse.type
  )(domainId: String, memberId: String)(
      extracted: TraceContext
  ): Future[ScanResource.GetMemberTrafficStatusResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getMemberTrafficStatus") { _ => _ =>
      for {
        member <- Member.fromProtoPrimitive_(memberId) match {
          case Right(member) => Future.successful(member)
          case Left(error) =>
            Future.failed(
              HttpErrorHandler.badRequest(s"Could not decode member ID: $error")
            )
        }
        domain <- DomainId.fromString(domainId) match {
          case Right(domain) => Future.successful(domain)
          case Left(error) =>
            Future.failed(
              HttpErrorHandler.badRequest(s"Could not decode domain ID: $error")
            )
        }
        sequencerResponse <- sequencerAdminConnection.getSequencerTrafficStatus(Seq(member))
        actual <- sequencerResponse.members match {
          case Seq() =>
            Future.failed(
              HttpErrorHandler.notFound(
                s"No traffic status found for member: $memberId"
              )
            )
          case Seq(m) => Future.successful(m.trafficState)
          case _ =>
            Future.failed(
              HttpErrorHandler.internalServerError(
                s"Received more than one traffic status response for member: $memberId."
              )
            )
        }
        actualConsumed = actual.extraTrafficConsumed.value
        actualLimit = actual.extraTrafficLimit.fold(0L)(_.value)
        targetTotalPurchased <- store.getTotalPurchasedMemberTraffic(member, domain)
      } yield {
        definitions.GetMemberTrafficStatusResponse(
          definitions.MemberTrafficStatus(
            definitions.ActualMemberTrafficState(actualConsumed, actualLimit),
            definitions.TargetMemberTrafficState(targetTotalPurchased),
          )
        )
      }
    }
  }
}
