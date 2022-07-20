package com.daml.network.wallet.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.network.examples.v0
import com.daml.network.examples.v0.WalletServiceGrpc.WalletServiceStub
import com.daml.network.wallet.CantonCoin
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import io.grpc.ManagedChannel

import scala.concurrent.Future

object WalletCommands {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = WalletServiceStub
    override def createService(channel: ManagedChannel): WalletServiceStub =
      v0.WalletServiceGrpc.stub(channel)
  }

  case class List() extends BaseCommand[v0.ListRequest, v0.ListResponse, Seq[CantonCoin]] {

    override def createRequest(): Either[String, v0.ListRequest] =
      Right(
        v0.ListRequest()
      )

    override def submitRequest(
        service: WalletServiceStub,
        request: v0.ListRequest,
    ): Future[v0.ListResponse] = service.list(request)

    override def handleResponse(
        response: v0.ListResponse
    ): Either[String, Seq[CantonCoin]] =
      response.coins.traverse(coin => CantonCoin.fromProto(coin)).leftMap(_.toString)
  }

}
