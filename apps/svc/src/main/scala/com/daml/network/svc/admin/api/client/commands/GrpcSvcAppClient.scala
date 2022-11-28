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

  case class StartSummarizingRound(round: Long)
      extends BaseCommand[
        v0.StartSummarizingRoundRequest,
        v0.StartSummarizingRoundResponse,
        roundCodegen.SummarizingMiningRound.ContractId,
      ] {
    override def createRequest(): Either[String, v0.StartSummarizingRoundRequest] = Right(
      v0.StartSummarizingRoundRequest(round)
    )

    override def submitRequest(
        service: SvcServiceStub,
        request: v0.StartSummarizingRoundRequest,
    ): Future[v0.StartSummarizingRoundResponse] =
      service.startSummarizingRound(request)

    override def handleResponse(
        response: v0.StartSummarizingRoundResponse
    ): Either[String, roundCodegen.SummarizingMiningRound.ContractId] =
      Proto.decodeJavaContractId(roundCodegen.SummarizingMiningRound.COMPANION)(
        response.summarizingMiningRoundContractId
      )
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
}
