package com.daml.network.sv.admin.api.client.commands

import org.apache.pekko.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer
import cats.data.EitherT
import cats.implicits.toTraverseOps
import cats.syntax.either.*
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import com.daml.network.admin.api.client.commands.{HttpClientBuilder, HttpCommand}
import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.codegen.java.cn.svc.coinprice.CoinPriceVote
import com.daml.network.codegen.java.cn.svcrules.{
  ActionRequiringConfirmation,
  Vote,
  VoteRequest,
  VoteResult,
}
import com.daml.network.codegen.java.cn.validatoronboarding.ValidatorOnboarding
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.environment.CNNodeStatus
import com.daml.network.http.v0.definitions.{
  CometBftNodeDumpResponse,
  TriggerDomainMigrationDumpRequest,
}
import com.daml.network.http.v0.sv_admin.{
  GetCometBftNodeDebugDumpResponse,
  TriggerDomainMigrationDumpResponse,
}
import com.daml.network.http.v0.{definitions, sv_admin as http}
import com.daml.network.sv.migration.{DomainDataSnapshot, DomainMigrationDump, DomainNodeIdentities}
import com.daml.network.util.{Codec, Contract, TemplateJsonDecoder}
import com.digitalasset.canton.health.admin.data.NodeStatus
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
        httpClient: HttpRequest => Future[HttpResponse],
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
        .traverse(req => Contract.fromHttp(ValidatorOnboarding.COMPANION)(req))
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
        .traverse(req => Contract.fromHttp(CoinPriceVote.COMPANION)(req))
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
        .traverse(req => Contract.fromHttp(OpenMiningRound.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case class CreateElectionRequest(
      requester: String,
      ranking: scala.collection.immutable.Vector[java.lang.String],
  ) extends BaseCommand[http.CreateElectionRequestResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.CreateElectionRequestResponse] =
      client.createElectionRequest(
        body = definitions.CreateElectionRequest(
          requester,
          ranking,
        ),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.CreateElectionRequestResponse.OK =>
      Right(())
    }
  }

  case class CreateVoteRequest(
      requester: String,
      action: ActionRequiringConfirmation,
      reasonUrl: String,
      reasonDescription: String,
      expiration: RelTime,
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
        .traverse(req => Contract.fromHttp(VoteRequest.COMPANION)(req))
        .leftMap(_.toString)
    }
  }

  case class ListVoteResults(
      actionName: Option[String],
      executed: Option[Boolean],
      requester: Option[String],
      effectiveFrom: Option[String],
      effectiveTo: Option[String],
      limit: BigInt,
  )() extends BaseCommand[http.ListSvcRulesVoteResultsResponse, Seq[
        VoteResult
      ]] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.ListSvcRulesVoteResultsResponse] =
      client.listSvcRulesVoteResults(
        body = definitions.ListVoteResultsRequest(
          actionName,
          executed,
          requester,
          effectiveFrom,
          effectiveTo,
          limit,
        ),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.ListSvcRulesVoteResultsResponse.OK(response) =>
      Right(
        response.svcRulesVoteResults
          .map(e =>
            decoder.decodeValue(
              VoteResult.valueDecoder(),
              VoteResult._packageId,
              "CN.SvcRules",
              "VoteResult",
            )(e)
          )
          .toSeq
      )
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
        .traverse(req => Contract.fromHttp(Vote.COMPANION)(req))
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
      CNNodeStatus.fromHttpNodeStatus(CNNodeStatus.fromHttp)(response)
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
      CNNodeStatus.fromHttpNodeStatus(CNNodeStatus.fromHttp)(response)
    }
  }

  case class PauseGlobalDomain() extends BaseCommand[http.PauseGlobalDomainResponse, Unit] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.PauseGlobalDomainResponse] =
      client.pauseGlobalDomain(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.PauseGlobalDomainResponse.OK =>
      Right(())
    }
  }

  case class TriggerDomainMigrationDump(migrationId: Long)
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
        body = TriggerDomainMigrationDumpRequest(migrationId),
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

  case class GetDomainDataSnapshot(timestamp: Instant, partyId: Option[PartyId])
      extends BaseCommand[
        http.GetDomainDataSnapshotResponse,
        DomainDataSnapshot,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetDomainDataSnapshotResponse] =
      client.getDomainDataSnapshot(
        body = definitions
          .GetDomainDataSnapshotRequest(timestamp.toString, partyId.map(_.toProtoPrimitive)),
        headers = headers,
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetDomainDataSnapshotResponse.OK(response) =>
      DomainDataSnapshot.fromHttp(response.dataSnapshot)
    }

  }

  case class GetDomainNodeIdentitiesDump()
      extends BaseCommand[
        http.GetDomainNodeIdentitiesDumpResponse,
        DomainNodeIdentities,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetDomainNodeIdentitiesDumpResponse] =
      client.getDomainNodeIdentitiesDump(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetDomainNodeIdentitiesDumpResponse.OK(response) =>
      DomainNodeIdentities.fromHttp(response.identities)
    }
  }

  case class GetDomainTime()
      extends BaseCommand[
        http.GetDomainTimeResponse,
        Instant,
      ] {
    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[
      Throwable,
      HttpResponse,
    ], http.GetDomainTimeResponse] =
      client.getDomainTime(headers = headers)

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ) = { case http.GetDomainTimeResponse.OK(response) =>
      Either.right(response.domainTime.toInstant)
    }
  }

  case class TriggerAcsDump()
      extends BaseCommand[
        http.TriggerAcsDumpResponse,
        definitions.TriggerAcsDumpResponse,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.TriggerAcsDumpResponse] =
      client.triggerAcsDump(
        headers = headers
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      http.TriggerAcsDumpResponse,
      Either[String, definitions.TriggerAcsDumpResponse],
    ] = { case http.TriggerAcsDumpResponse.OK(response) =>
      Either.right(response)
    }
  }

  case class GetAcsStoreDump()
      extends BaseCommand[
        http.GetAcsStoreDumpResponse,
        definitions.GetAcsStoreDumpResponse,
      ] {

    override def submitRequest(
        client: Client,
        headers: List[HttpHeader],
    ): EitherT[Future, Either[Throwable, HttpResponse], http.GetAcsStoreDumpResponse] =
      client.getAcsStoreDump(
        headers = headers
      )

    override def handleOk()(implicit
        decoder: TemplateJsonDecoder
    ): PartialFunction[
      http.GetAcsStoreDumpResponse,
      Either[String, definitions.GetAcsStoreDumpResponse],
    ] = { case http.GetAcsStoreDumpResponse.OK(response) =>
      Either.right(response)
    }
  }
}
