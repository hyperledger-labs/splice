// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import org.lfdecentralizedtrust.splice.admin.http.HttpErrorHandler
import org.lfdecentralizedtrust.splice.environment.SequencerAdminConnection
import org.lfdecentralizedtrust.splice.http.v0.{definitions, external}
import org.lfdecentralizedtrust.splice.http.v0.external.scan.ScanResource
import org.lfdecentralizedtrust.splice.scan.store.ScanStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{SynchronizerId, Member, PartyId}
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
  )(synchronizerId: String, memberId: String)(
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
        domain <- SynchronizerId.fromString(synchronizerId) match {
          case Right(domain) => Future.successful(domain)
          case Left(error) =>
            Future.failed(
              HttpErrorHandler.badRequest(s"Could not decode domain ID: $error")
            )
        }
        actual <- sequencerAdminConnection.getSequencerTrafficControlState(member)
        actualConsumed = actual.extraTrafficConsumed.value
        actualLimit = actual.extraTrafficLimit.value
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

  override def getPartyToParticipant(respond: ScanResource.GetPartyToParticipantResponse.type)(
      synchronizerId: String,
      partyId: String,
  )(extracted: TraceContext): Future[ScanResource.GetPartyToParticipantResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getPartyToParticipant") { _ => _ =>
      for {
        domain <- SynchronizerId.fromString(synchronizerId) match {
          case Right(domain) => Future.successful(domain)
          case Left(error) =>
            Future.failed(
              HttpErrorHandler.badRequest(s"Could not decode domain ID: $error")
            )
        }
        party <- PartyId.fromProtoPrimitive(partyId, "partyId") match {
          case Right(party) => Future.successful(party)
          case Left(error) =>
            Future.failed(
              HttpErrorHandler.badRequest(s"Could not decode party ID: $error")
            )
        }
        response <- sequencerAdminConnection.getPartyToParticipant(domain, party)
        participantId <- response.mapping.participantIds match {
          case Seq() =>
            Future.failed(
              HttpErrorHandler.notFound(
                s"No participant id found hosting party: $party"
              )
            )
          case Seq(participantId) => Future.successful(participantId)
          case _ =>
            Future.failed(
              HttpErrorHandler.internalServerError(
                s"Party ${party} is hosted on multiple participants, which is not currently supported"
              )
            )
        }
      } yield definitions.GetPartyToParticipantResponse(participantId.toProtoPrimitive)
    }
  }

  override def getValidatorFaucetsByValidator(
      respond: ScanResource.GetValidatorFaucetsByValidatorResponse.type
  )(validators: Vector[String])(
      extracted: TraceContext
  ): Future[ScanResource.GetValidatorFaucetsByValidatorResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getValidatorFaucetsByValidator") { _ => _ =>
      store
        .getValidatorLicenseByValidator(validators.map(v => PartyId.tryFromProtoPrimitive(v)))
        .map(licenses =>
          ScanResource.GetValidatorFaucetsByValidatorResponse.OK(
            definitions
              .GetValidatorFaucetsByValidatorResponse(
                FaucetProcessor.process(licenses)
              )
          )
        )
    }
  }
}
