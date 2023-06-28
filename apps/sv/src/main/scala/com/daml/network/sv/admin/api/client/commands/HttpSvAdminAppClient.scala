package com.daml.network.sv.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import cats.implicits.toTraverseOps
import cats.syntax.either.*
import com.daml.lf.value.json.ApiCodecCompressed
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.codegen.java.cn.svc.coinprice.CoinPriceVote
import com.daml.network.codegen.java.cn.svcrules.{ActionRequiringConfirmation, Vote, VoteRequest}
import com.daml.network.codegen.java.cn.validatoronboarding.ValidatorOnboarding
import com.daml.network.environment.CNNodeStatus
import com.daml.network.http.v0.definitions.{CometBftNodeDumpResponse}
import com.daml.network.http.v0.svAdmin.{GetCometBftNodeDebugDumpResponse}
import com.daml.network.http.v0.{definitions, svAdmin as http}
import com.daml.network.util.{Codec, Contract, TemplateJsonDecoder}
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}

object HttpSvAdminAppClient {
  abstract class BaseCommand[Res, Result] extends HttpCommand[Res, Result] {
    override type Client = http.SvAdminClient

    def createClient(host: String)(implicit
        httpClient: HttpRequest => Future[HttpResponse],
        tc: TraceContext,
        ec: ExecutionContext,
        mat: Materializer,
    ): Client =
      http.SvAdminClient.httpClient(
        HttpClientBuilder().buildClient,
        host,
      )
  }

  case object ListOngoingValidatorOnboardings
      extends BaseCommand[http.ListOngoingValidatorOnboardingsResponse, Seq[
        Contract[ValidatorOnboarding.ContractId, ValidatorOnboarding]
      ]] {
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
        .traverse(req => Contract.fromJson(ValidatorOnboarding.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case class PrepareValidatorOnboarding(expiresIn: FiniteDuration)
      extends BaseCommand[http.PrepareValidatorOnboardingResponse, String] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.PrepareValidatorOnboardingResponse] =
      client.prepareValidatorOnboarding(
        body = definitions.PrepareValidatorOnboardingRequest(expiresIn.toSeconds),
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

  case class ApproveSvIdentity(candidateName: String, candidateKey: String)
      extends BaseCommand[http.ApproveSvIdentityResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ApproveSvIdentityResponse] =
      client.approveSvIdentity(
        body = definitions.ApproveSvIdentityRequest(candidateName, candidateKey),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ApproveSvIdentityResponse.OK =>
      Right(())
    }
  }

  case object ListCoinPriceVotes
      extends BaseCommand[http.ListCoinPriceVotesResponse, Seq[
        Contract[CoinPriceVote.ContractId, CoinPriceVote]
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListCoinPriceVotesResponse] =
      client.listCoinPriceVotes(
        headers = headers
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListCoinPriceVotesResponse.OK(response) =>
      response.coinPriceVotes
        .traverse(req => Contract.fromJson(CoinPriceVote.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case class UpdateCoinPriceVote(coinPrice: BigDecimal)
      extends BaseCommand[http.UpdateCoinPriceVoteResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.UpdateCoinPriceVoteResponse] =
      client.updateCoinPriceVote(
        body = definitions.UpdateCoinPriceVoteRequest(Codec.encode(coinPrice)),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.UpdateCoinPriceVoteResponse.OK =>
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
        .traverse(req => Contract.fromJson(OpenMiningRound.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case class CreateVoteRequest(
      requester: String,
      action: ActionRequiringConfirmation,
      reasonUrl: String,
      reasonDescription: String,
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
      extends BaseCommand[http.ListSvcRulesVoteRequestsResponse, Seq[
        Contract[VoteRequest.ContractId, VoteRequest]
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListSvcRulesVoteRequestsResponse] =
      client.listSvcRulesVoteRequests(
        headers = headers
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListSvcRulesVoteRequestsResponse.OK(response) =>
      response.svcRulesVoteRequests
        .traverse(req => Contract.fromJson(VoteRequest.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case class CastVote(
      voteRequestCid: VoteRequest.ContractId,
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
          .CastVoteRequest(voteRequestCid.contractId, isAccepted, reasonUrl, reasonDescription),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.CastVoteResponse.Created =>
      Right(())
    }
  }

  case class UpdateVote(
      voteRequestCid: VoteRequest.ContractId,
      isAccepted: Boolean,
      reasonUrl: String,
      reasonDescription: String,
  ) extends BaseCommand[http.UpdateVoteResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.UpdateVoteResponse] =
      client.updateVote(
        body = definitions
          .UpdateVoteRequest(voteRequestCid.contractId, isAccepted, reasonUrl, reasonDescription),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.UpdateVoteResponse.OK =>
      Right(())
    }
  }

  case class ListVotes(
      voteRequestContractIds: Vector[String]
  ) extends BaseCommand[http.BatchListVotesByVoteRequestsResponse, Seq[
        Contract[Vote.ContractId, Vote]
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.BatchListVotesByVoteRequestsResponse] =
      client.batchListVotesByVoteRequests(
        body = definitions.BatchListVotesByVoteRequestsRequest(voteRequestContractIds),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.BatchListVotesByVoteRequestsResponse.OK(response) =>
      response.svcRulesVotes
        .traverse(req => Contract.fromJson(Vote.COMPANION)(req))
        .leftMap(_.toString)
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
    ] = { case http.GetCometBftNodeDebugDumpResponse.OK(response) =>
      response.response.toRight(response.error.map(_.error).getOrElse("No response found"))
    }
  }

  case class GetSequencerNodeStatus()
      extends BaseCommand[
        http.GetSequencerNodeStatusResponse,
        NodeStatus[CNNodeStatus],
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
      Either[String, NodeStatus[CNNodeStatus]],
    ] = { case http.GetSequencerNodeStatusResponse.OK(response) =>
      CNNodeStatus.fromJsonNodeStatus(CNNodeStatus.fromJsonV0)(response)
    }
  }

  case class GetMediatorNodeStatus()
      extends BaseCommand[
        http.GetMediatorNodeStatusResponse,
        NodeStatus[CNNodeStatus],
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
      Either[String, NodeStatus[CNNodeStatus]],
    ] = { case http.GetMediatorNodeStatusResponse.OK(response) =>
      CNNodeStatus.fromJsonNodeStatus(CNNodeStatus.fromJsonV0)(response)
    }
  }
}
