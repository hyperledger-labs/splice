package com.daml.network.svc.admin.api.client.commands

import cats.implicits._
import com.daml.ledger.client.binding.Primitive.ContractId
import com.daml.network.svc.v0
import com.daml.network.svc.v0.SvcServiceGrpc.SvcServiceStub
import com.daml.network.svc.v0.{GetDebugInfoResponse, GetValidatorConfigResponse}
import com.daml.network.util.Proto
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.CC.{Round => roundCodegen}
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object SvcAppCommands {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = SvcServiceStub
    override def createService(channel: ManagedChannel): SvcServiceStub =
      v0.SvcServiceGrpc.stub(channel)
  }

  /** A command that takes no input and returns no result (other than an error on failure) */
  // TODO(Arne): Move this somewhere to Canton codebase?
  abstract class UnitCommand(adminApiCall: SvcServiceStub => (Empty => Future[Empty]))
      extends BaseCommand[Empty, Empty, Unit] {
    override def createRequest(): Either[String, Empty] = Right(Empty())
    override def submitRequest(
        service: SvcServiceStub,
        request: Empty,
    ): Future[Empty] = adminApiCall(service)(request)
    override def handleResponse(
        response: Empty
    ): Either[String, Unit] = Right(())
  }

  case class AcceptValidators() extends UnitCommand(_.acceptValidators)

  case class DebugInfo(
      svcUser: String,
      svcParty: PartyId,
      coinPackageId: String,
      coinRulesCids: Seq[String],
  )

  case class GetDebugInfo() extends BaseCommand[Empty, GetDebugInfoResponse, DebugInfo] {
    override def createRequest(): Either[String, Empty] = Right(Empty())
    override def submitRequest(
        service: SvcServiceStub,
        request: Empty,
    ): Future[GetDebugInfoResponse] = service.getDebugInfo(request)
    override def handleResponse(
        response: GetDebugInfoResponse
    ): Either[String, DebugInfo] =
      Proto.decode(Proto.Party)(response.svcPartyId).map { svc =>
        DebugInfo(
          svcUser = response.svcUser,
          svcParty = svc,
          coinPackageId = response.coinPackageId,
          coinRulesCids = response.coinRulesContractIds,
        )
      }
  }

  case class ValidatorConfigInfo(
      svcParty: PartyId
  )

  case class GetValidatorConfig()
      extends BaseCommand[Empty, GetValidatorConfigResponse, ValidatorConfigInfo] {
    override def createRequest(): Either[String, Empty] = Right(Empty())
    override def submitRequest(
        service: SvcServiceStub,
        request: Empty,
    ): Future[GetValidatorConfigResponse] = service.getValidatorConfig(request)
    override def handleResponse(
        response: GetValidatorConfigResponse
    ): Either[String, ValidatorConfigInfo] =
      Proto.decode(Proto.Party)(response.svcPartyId).map { svc =>
        ValidatorConfigInfo(
          svcParty = svc
        )
      }
  }

  private object RoundCommand {
    def decodeRoundMap[T](m: Map[String, String]): Either[String, Map[PartyId, ContractId[T]]] =
      m.toList
        .traverse { case (k, v) =>
          for {
            party <- Proto.decode(Proto.Party)(k)
            contractId <- Proto.decodeContractId[T](v)
          } yield party -> contractId
        }
        .map(_.toMap)
  }

  case class OpenRound(coinPrice: BigDecimal)
      extends BaseCommand[v0.OpenRoundRequest, v0.OpenRoundResponse, Map[PartyId, ContractId[
        roundCodegen.OpenMiningRound
      ]]] {
    override def createRequest(): Either[String, v0.OpenRoundRequest] = Right(
      v0.OpenRoundRequest(Proto.encode(coinPrice))
    )
    override def submitRequest(
        service: SvcServiceStub,
        request: v0.OpenRoundRequest,
    ): Future[v0.OpenRoundResponse] =
      service.openRound(request)
    override def handleResponse(
        response: v0.OpenRoundResponse
    ): Either[String, Map[PartyId, ContractId[roundCodegen.OpenMiningRound]]] =
      RoundCommand.decodeRoundMap(response.openMiningRoundContractIds)
  }

  case class StartClosingRound(round: Long)
      extends BaseCommand[
        v0.StartClosingRoundRequest,
        v0.StartClosingRoundResponse,
        Map[PartyId, ContractId[roundCodegen.ClosingMiningRound]],
      ] {
    override def createRequest(): Either[String, v0.StartClosingRoundRequest] = Right(
      v0.StartClosingRoundRequest(round)
    )
    override def submitRequest(
        service: SvcServiceStub,
        request: v0.StartClosingRoundRequest,
    ): Future[v0.StartClosingRoundResponse] =
      service.startClosingRound(request)
    override def handleResponse(
        response: v0.StartClosingRoundResponse
    ): Either[String, Map[PartyId, ContractId[roundCodegen.ClosingMiningRound]]] =
      RoundCommand.decodeRoundMap(response.closingMiningRoundContractIds)
  }

  case class StartIssuingRoundResponse(
      totalBurnQuantity: BigDecimal,
      validatorRounds: Map[PartyId, ContractId[roundCodegen.IssuingMiningRound]],
  )

  case class StartIssuingRound(round: Long)
      extends BaseCommand[
        v0.StartIssuingRoundRequest,
        v0.StartIssuingRoundResponse,
        StartIssuingRoundResponse,
      ] {
    override def createRequest(): Either[String, v0.StartIssuingRoundRequest] = Right(
      v0.StartIssuingRoundRequest(round)
    )
    override def submitRequest(
        service: SvcServiceStub,
        request: v0.StartIssuingRoundRequest,
    ): Future[v0.StartIssuingRoundResponse] =
      service.startIssuingRound(request)
    override def handleResponse(
        response: v0.StartIssuingRoundResponse
    ): Either[String, StartIssuingRoundResponse] = for {
      totalBurnQuantity <- Proto.decode(Proto.BigDecimal)(response.totalBurnQuantity)
      validatorRounds <- RoundCommand.decodeRoundMap(response.issuingMiningRoundContractIds)
    } yield StartIssuingRoundResponse(totalBurnQuantity, validatorRounds)
  }

  case class CloseRound(round: Long)
      extends BaseCommand[v0.CloseRoundRequest, v0.CloseRoundResponse, Map[PartyId, ContractId[
        roundCodegen.ClosedMiningRound
      ]]] {
    override def createRequest(): Either[String, v0.CloseRoundRequest] = Right(
      v0.CloseRoundRequest(round)
    )
    override def submitRequest(
        service: SvcServiceStub,
        request: v0.CloseRoundRequest,
    ): Future[v0.CloseRoundResponse] =
      service.closeRound(request)
    override def handleResponse(
        response: v0.CloseRoundResponse
    ): Either[String, Map[PartyId, ContractId[roundCodegen.ClosedMiningRound]]] =
      RoundCommand.decodeRoundMap(response.closedMiningRoundContractIds)
  }

  case class ArchiveRound(round: Long) extends BaseCommand[v0.ArchiveRoundRequest, Empty, Unit] {
    override def createRequest(): Either[String, v0.ArchiveRoundRequest] = Right(
      v0.ArchiveRoundRequest(round)
    )
    override def submitRequest(
        service: SvcServiceStub,
        request: v0.ArchiveRoundRequest,
    ): Future[Empty] =
      service.archiveRound(request)
    override def handleResponse(response: Empty): Either[String, Unit] = Right(())
  }
}
