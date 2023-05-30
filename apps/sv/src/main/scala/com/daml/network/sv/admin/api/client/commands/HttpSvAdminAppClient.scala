package com.daml.network.sv.admin.api.client.commands

import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.EitherT
import cats.implicits.{toBifunctorOps, toTraverseOps}
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.codegen.java.cn.svc.coinprice.CoinPriceVote
import com.daml.network.codegen.java.cn.validatoronboarding.ValidatorOnboarding
import com.daml.network.environment.CNNodeStatus
import com.daml.network.http.v0.definitions.{CometBftNodeDumpResponse, CometBftNodeStatusResponse}
import com.daml.network.http.v0.svAdmin.{
  GetCometBftNodeDebugDumpResponse,
  GetCometBftNodeStatusResponse,
}
import com.daml.network.http.v0.{definitions, svAdmin as http}
import com.daml.network.util.{Codec, Contract, TemplateJsonDecoder}
import com.digitalasset.canton.health.admin.data.NodeStatus
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

  case class IsAuthorized() extends BaseCommand[http.IsAuthorizedResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.IsAuthorizedResponse] =
      client.isAuthorized(
        headers = headers
      )

    override def handleResponse(response: http.IsAuthorizedResponse)(implicit
        decoder: TemplateJsonDecoder
    ): Either[String, Unit] = response match {
      case http.IsAuthorizedResponse.OK => Right(())
      case http.IsAuthorizedResponse.Forbidden(e) => Left(e.error)
    }

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.IsAuthorizedResponse.OK =>
      Right(())
    }
  }

  case class GetCometBftNodeStatus()
      extends BaseCommand[
        http.GetCometBftNodeStatusResponse,
        definitions.CometBftNodeStatusResponse,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetCometBftNodeStatusResponse] =
      client.getCometBftNodeStatus(
        headers = headers
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      GetCometBftNodeStatusResponse,
      Either[String, CometBftNodeStatusResponse],
    ] = { case http.GetCometBftNodeStatusResponse.OK(response) =>
      response.response.toRight(response.error.map(_.error).getOrElse("No response found"))
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
