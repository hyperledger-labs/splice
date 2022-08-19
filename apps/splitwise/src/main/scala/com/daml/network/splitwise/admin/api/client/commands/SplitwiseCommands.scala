package com.daml.network.splitwise.admin.api.client.commands
import com.daml.network.splitwise.v0
import com.daml.network.splitwise.v0.SplitwiseServiceGrpc.SplitwiseServiceStub
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import io.grpc.ManagedChannel

object SplitwiseCommands {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = SplitwiseServiceStub
    override def createService(channel: ManagedChannel): SplitwiseServiceStub =
      v0.SplitwiseServiceGrpc.stub(channel)
  }
}
