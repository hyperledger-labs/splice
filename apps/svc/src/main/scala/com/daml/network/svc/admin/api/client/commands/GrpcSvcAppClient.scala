package com.daml.network.svc.admin.api.client.commands

import com.daml.network.codegen.java.cc.{round => roundCodegen}
import com.daml.network.svc.v0
import com.daml.network.svc.v0.GetDebugInfoResponse
import com.daml.network.svc.v0.SvcServiceGrpc.SvcServiceStub
import com.daml.network.util.Proto
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object GrpcSvcAppClient {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = SvcServiceStub
    override def createService(channel: ManagedChannel): SvcServiceStub =
      v0.SvcServiceGrpc.stub(channel)
  }

  /** A command that takes no input and returns no result (other than an error on failure) */
  // TODO(M1-92): Move this somewhere to Canton codebase?
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

  case class OpenRound(round: Long, coinPrice: BigDecimal)
      extends BaseCommand[
        v0.OpenRoundRequest,
        v0.OpenRoundResponse,
        roundCodegen.OpenMiningRound.ContractId,
      ] {
    override def createRequest(): Either[String, v0.OpenRoundRequest] = Right(
      v0.OpenRoundRequest(round, Proto.encode(coinPrice))
    )
    override def submitRequest(
        service: SvcServiceStub,
        request: v0.OpenRoundRequest,
    ): Future[v0.OpenRoundResponse] =
      service.openRound(request)
    override def handleResponse(
        response: v0.OpenRoundResponse
    ): Either[String, roundCodegen.OpenMiningRound.ContractId] =
      Proto.decodeJavaContractId(roundCodegen.OpenMiningRound.COMPANION)(
        response.openMiningRoundContractId
      )
  }

  case class StartClosingRound(round: Long)
      extends BaseCommand[
        v0.StartClosingRoundRequest,
        v0.StartClosingRoundResponse,
        roundCodegen.ClosingMiningRound.ContractId,
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
    ): Either[String, roundCodegen.ClosingMiningRound.ContractId] =
      Proto.decodeJavaContractId(roundCodegen.ClosingMiningRound.COMPANION)(
        response.closingMiningRoundContractId
      )
  }

  case class StartIssuingRoundResponse(
      totalBurnQuantity: BigDecimal,
      issuingRound: roundCodegen.IssuingMiningRound.ContractId,
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
      round <- Proto.decodeJavaContractId(roundCodegen.IssuingMiningRound.COMPANION)(
        response.issuingMiningRoundContractId
      )
    } yield StartIssuingRoundResponse(totalBurnQuantity, round)
  }

  case class CloseRound(round: Long)
      extends BaseCommand[
        v0.CloseRoundRequest,
        v0.CloseRoundResponse,
        roundCodegen.ClosedMiningRound.ContractId,
      ] {
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
    ): Either[String, roundCodegen.ClosedMiningRound.ContractId] =
      Proto.decodeJavaContractId(roundCodegen.ClosedMiningRound.COMPANION)(
        response.closedMiningRoundContractId
      )
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
