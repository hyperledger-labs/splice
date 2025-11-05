// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import cats.implicits.toTraverseOps
import cats.syntax.either.*
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import org.lfdecentralizedtrust.splice.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.amuletprice.AmuletPriceVote
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRules_CloseVoteRequestResult,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatoronboarding as vo
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.environment.SpliceStatus
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  CometBftNodeDumpResponse,
  TriggerDomainMigrationDumpRequest,
}
import org.lfdecentralizedtrust.splice.http.v0.sv_admin.{
  GetCometBftNodeDebugDumpResponse,
  TriggerDomainMigrationDumpResponse,
}
import org.lfdecentralizedtrust.splice.http.v0.{definitions, sv_admin as http}
import org.lfdecentralizedtrust.splice.sv.migration.{
  DomainDataSnapshot,
  DomainMigrationDump,
  SynchronizerNodeIdentities,
}
import org.lfdecentralizedtrust.splice.util.{Codec, Contract, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.sv.util.ValidatorOnboarding
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

object HttpSvAdminAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.SvAdminClient

    def createClient(host: String)(implicit
        httpClient: HttpClient,
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.SvAdminClient.httpClient(
        HttpClientBuilder().buildClient(),
        host,
      )
  }

  case object ListOngoingValidatorOnboardings
      extends BaseCommand[http.ListOngoingValidatorOnboardingsResponse, Seq[ValidatorOnboarding]] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.ListOngoingValidatorOnboardingsResponse] =
      client.listOngoingValidatorOnboardings(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListOngoingValidatorOnboardingsResponse.OK(response) =>
      response.ongoingValidatorOnboardings
        .traverse { req =>
          Contract
            .fromHttp(vo.ValidatorOnboarding.COMPANION)(req.contract)
            .map(c =>
              ValidatorOnboarding(
                req.encodedSecret,
                c,
              )
            )
        }
        .leftMap(_.toString)
    }
  }

  case class PrepareValidatorOnboarding(expiresIn: FiniteDuration, partyHint: Option[String])
      extends BaseCommand[http.PrepareValidatorOnboardingResponse, String] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.PrepareValidatorOnboardingResponse] =
      client.prepareValidatorOnboarding(
        body = definitions.PrepareValidatorOnboardingRequest(expiresIn.toSeconds, partyHint),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = {
      case http.PrepareValidatorOnboardingResponse.OK(
            definitions.PrepareValidatorOnboardingResponse(secret)
          ) =>
        Right(secret)
    }
  }

  case object ListAmuletPriceVotes
      extends BaseCommand[http.ListAmuletPriceVotesResponse, Seq[
        Contract[AmuletPriceVote.ContractId, AmuletPriceVote]
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListAmuletPriceVotesResponse] =
      client.listAmuletPriceVotes(
        headers = headers
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListAmuletPriceVotesResponse.OK(response) =>
      response.amuletPriceVotes
        .traverse(req => Contract.fromHttp(AmuletPriceVote.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case class UpdateAmuletPriceVote(amuletPrice: BigDecimal)
      extends BaseCommand[http.UpdateAmuletPriceVoteResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.UpdateAmuletPriceVoteResponse] =
      client.updateAmuletPriceVote(
        body = definitions.UpdateAmuletPriceVoteRequest(Codec.encode(amuletPrice)),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.UpdateAmuletPriceVoteResponse.OK =>
      Right(())
    }
  }

  case object ListOpenMiningRounds
      extends BaseCommand[http.ListOpenMiningRoundsResponse, Seq[
        Contract[OpenMiningRound.ContractId, OpenMiningRound]
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListOpenMiningRoundsResponse] =
      client.listOpenMiningRounds(
        headers = headers
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListOpenMiningRoundsResponse.OK(response) =>
      response.openMiningRounds
        .traverse(req => Contract.fromHttp(OpenMiningRound.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case class CreateVoteRequest(
      requester: String,
      action: ActionRequiringConfirmation,
      reasonUrl: String,
      reasonDescription: String,
      expiration: RelTime,
      effectiveTime: Option[Instant],
  )(implicit elc: ErrorLoggingContext)
      extends BaseCommand[http.CreateVoteRequestResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.CreateVoteRequestResponse] =
      client.createVoteRequest(
        body = definitions.CreateVoteRequest(
          requester,
          io.circe.parser
            .parse(
              ApiCodecCompressed
                .apiValueToJsValue(Contract.javaValueToLfValue(action.toValue))
                .compactPrint
            )
            .valueOr(error => throw new IllegalArgumentException(error)),
          reasonUrl,
          reasonDescription,
          io.circe.parser
            .parse(
              ApiCodecCompressed
                .apiValueToJsValue(Contract.javaValueToLfValue(expiration.toValue))
                .compactPrint
            )
            .valueOr(error => throw new IllegalArgumentException(error)),
          effectiveTime match {
            case None => None
            case Some(time) => Some(time.atOffset(java.time.ZoneOffset.UTC))
          },
        ),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.CreateVoteRequestResponse.OK =>
      Right(())
    }
  }

  case object ListVoteRequests
      extends BaseCommand[http.ListDsoRulesVoteRequestsResponse, Seq[
        Contract[VoteRequest.ContractId, VoteRequest]
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListDsoRulesVoteRequestsResponse] =
      client.listDsoRulesVoteRequests(
        headers = headers
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListDsoRulesVoteRequestsResponse.OK(response) =>
      response.dsoRulesVoteRequests
        .traverse(req => Contract.fromHttp(VoteRequest.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case class LookupVoteRequest(trackingCid: VoteRequest.ContractId)()
      extends BaseCommand[
        http.LookupDsoRulesVoteRequestResponse,
        Contract[VoteRequest.ContractId, VoteRequest],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.LookupDsoRulesVoteRequestResponse] =
      client.lookupDsoRulesVoteRequest(
        trackingCid.contractId,
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.LookupDsoRulesVoteRequestResponse.OK(response) =>
      Contract
        .fromHttp(VoteRequest.COMPANION)(response.dsoRulesVoteRequest)
        .leftMap(_.toString)
    }
  }

  case class ListVoteRequestResults(
      actionName: Option[String],
      accepted: Option[Boolean],
      requester: Option[String],
      effectiveFrom: Option[String],
      effectiveTo: Option[String],
      limit: BigInt,
  ) extends BaseCommand[http.ListVoteRequestResultsResponse, Seq[
        DsoRules_CloseVoteRequestResult
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListVoteRequestResultsResponse] =
      client.listVoteRequestResults(
        body = definitions.ListVoteResultsRequest(
          actionName,
          accepted,
          requester,
          effectiveFrom,
          effectiveTo,
          limit,
        ),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListVoteRequestResultsResponse.OK(response) =>
      Right(
        response.dsoRulesVoteResults
          .map(e =>
            decoder.decodeValue(
              DsoRules_CloseVoteRequestResult.valueDecoder(),
              DsoRules_CloseVoteRequestResult._packageId,
              "Splice.DsoRules",
              "DsoRules_CloseVoteRequestResult",
            )(e)
          )
          .toSeq
      )
    }
  }

  case class CastVote(
      trackingCid: VoteRequest.ContractId,
      isAccepted: Boolean,
      reasonUrl: String,
      reasonDescription: String,
  ) extends BaseCommand[http.CastVoteResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.CastVoteResponse] =
      client.castVote(
        body = definitions
          .CastVoteRequest(trackingCid.contractId, isAccepted, reasonUrl, reasonDescription),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.CastVoteResponse.Created =>
      Right(())
    }
  }

  case class GetCometBftNodeDump()
      extends BaseCommand[
        http.GetCometBftNodeDebugDumpResponse,
        definitions.CometBftNodeDumpResponse,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetCometBftNodeDebugDumpResponse] =
      client.getCometBftNodeDebugDump(
        headers = headers
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      GetCometBftNodeDebugDumpResponse,
      Either[String, CometBftNodeDumpResponse],
    ] = {
      case http.GetCometBftNodeDebugDumpResponse.OK(
            definitions.CometBftNodeDumpOrErrorResponse.members.CometBftNodeDumpResponse(response)
          ) =>
        Right(response)
      case http.GetCometBftNodeDebugDumpResponse.OK(
            definitions.CometBftNodeDumpOrErrorResponse.members.ErrorResponse(response)
          ) =>
        Left(response.error)
    }
  }

  case class GetSequencerNodeStatus()
      extends BaseCommand[
        http.GetSequencerNodeStatusResponse,
        NodeStatus[SpliceStatus],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetSequencerNodeStatusResponse] =
      client.getSequencerNodeStatus(
        headers = headers
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      http.GetSequencerNodeStatusResponse,
      Either[String, NodeStatus[SpliceStatus]],
    ] = { case http.GetSequencerNodeStatusResponse.OK(response) =>
      SpliceStatus.fromHttpNodeStatus(SpliceStatus.fromHttp)(response)
    }
  }

  case class GetMediatorNodeStatus()
      extends BaseCommand[
        http.GetMediatorNodeStatusResponse,
        NodeStatus[SpliceStatus],
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetMediatorNodeStatusResponse] =
      client.getMediatorNodeStatus(
        headers = headers
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      http.GetMediatorNodeStatusResponse,
      Either[String, NodeStatus[SpliceStatus]],
    ] = { case http.GetMediatorNodeStatusResponse.OK(response) =>
      SpliceStatus.fromHttpNodeStatus(SpliceStatus.fromHttp)(response)
    }
  }

  case class PauseDecentralizedSynchronizer()
      extends BaseCommand[http.PauseDecentralizedSynchronizerResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.PauseDecentralizedSynchronizerResponse] =
      client.pauseDecentralizedSynchronizer(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.PauseDecentralizedSynchronizerResponse.OK =>
      Right(())
    }
  }

  case class UnpauseDecentralizedSynchronizer()
      extends BaseCommand[http.UnpauseDecentralizedSynchronizerResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.UnpauseDecentralizedSynchronizerResponse] =
      client.unpauseDecentralizedSynchronizer(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.UnpauseDecentralizedSynchronizerResponse.OK =>
      Right(())
    }
  }

  case class TriggerDomainMigrationDump(migrationId: Long, at: Option[Instant])
      extends BaseCommand[
        http.TriggerDomainMigrationDumpResponse,
        Unit,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], TriggerDomainMigrationDumpResponse] =
      client.triggerDomainMigrationDump(
        headers = headers,
        body = TriggerDomainMigrationDumpRequest(migrationId, at.map(_.toString)),
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.TriggerDomainMigrationDumpResponse.OK =>
      Right(())
    }
  }
  case class GetDomainMigrationDump()
      extends BaseCommand[
        http.GetDomainMigrationDumpResponse,
        DomainMigrationDump,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetDomainMigrationDumpResponse] =
      client.getDomainMigrationDump(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetDomainMigrationDumpResponse.OK(response) =>
      DomainMigrationDump.fromHttp(response)
    }
  }

  case class GetDomainDataSnapshot(
      timestamp: Instant,
      partyId: Option[PartyId],
      migrationId: Option[Long],
      force: Boolean,
  ) extends BaseCommand[
        http.GetDomainDataSnapshotResponse,
        DomainDataSnapshot.Response,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetDomainDataSnapshotResponse] =
      client.getDomainDataSnapshot(
        timestamp.toString,
        partyId.map(_.toProtoPrimitive),
        migrationId = migrationId,
        force = Some(force),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetDomainDataSnapshotResponse.OK(response) =>
      DomainDataSnapshot.Response.fromHttp(response)
    }

  }

  case class GetSynchronizerNodeIdentitiesDump()
      extends BaseCommand[
        http.GetSynchronizerNodeIdentitiesDumpResponse,
        SynchronizerNodeIdentities,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetSynchronizerNodeIdentitiesDumpResponse] =
      client.getSynchronizerNodeIdentitiesDump(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetSynchronizerNodeIdentitiesDumpResponse.OK(response) =>
      SynchronizerNodeIdentities.fromHttp(response.identities)
    }
  }
}
