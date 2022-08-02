package com.daml.network.scan.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding.Primitive
import com.daml.network.scan.v0
import com.daml.network.scan.v0.ScanServiceGrpc.ScanServiceStub
import com.daml.network.util.Contract
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.topology.PartyId
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object ScanCommands {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = ScanServiceStub
    override def createService(channel: ManagedChannel): ScanServiceStub =
      v0.ScanServiceGrpc.stub(channel)
  }

  final case class GetSvcPartyId() extends BaseCommand[Empty, v0.GetSvcPartyIdResponse, PartyId] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())
    override def submitRequest(service: ScanServiceStub, req: Empty): Future[v0.GetSvcPartyIdResponse] =
      service.getSvcPartyId(req)
    override def handleResponse(response: v0.GetSvcPartyIdResponse): Either[String, PartyId] =
      PartyId.fromProtoPrimitive(response.svcPartyId)
  }
}
