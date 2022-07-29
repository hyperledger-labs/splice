package com.daml.network.directory.user.admin.api.client.commands

import cats.syntax.either._
import cats.syntax.traverse._
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding.Primitive
import com.daml.network.directory_user.v0
import com.daml.network.directory_user.v0.DirectoryUserServiceGrpc.DirectoryUserServiceStub
import com.daml.network.util.Contract
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.serialization.ProtoConverter
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.CN.{Directory => codegen, Wallet => walletCodegen}
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object DirectoryUserCommands {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = DirectoryUserServiceStub
    override def createService(channel: ManagedChannel): DirectoryUserServiceStub =
      v0.DirectoryUserServiceGrpc.stub(channel)
  }
}
