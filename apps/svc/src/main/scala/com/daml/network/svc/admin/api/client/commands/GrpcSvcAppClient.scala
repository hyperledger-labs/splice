package com.daml.network.svc.admin.api.client.commands

import com.daml.ledger.api.v1.value as scalaValue
import com.daml.ledger.javaapi.data.Timestamp
import com.daml.network.codegen.java.cc.coinconfig.{CoinConfig, USD}
import com.daml.network.codegen.java.cc.schedule.Schedule
import com.daml.network.svc.v0
import com.daml.network.svc.v0.{GetDebugInfoResponse, SetConfigScheduleRequest}
import com.daml.network.svc.v0.SvcServiceGrpc.SvcServiceStub
import com.daml.network.util.Codec
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.PartyId
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import java.time.Instant
import scala.concurrent.Future

object GrpcSvcAppClient {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = SvcServiceStub

    override def createService(channel: ManagedChannel): SvcServiceStub =
      v0.SvcServiceGrpc.stub(channel)
  }

  case class DebugInfo(
      svcUser: String,
      svcParty: PartyId,
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
      Codec.decode(Codec.Party)(response.svcPartyId).map { svc =>
        DebugInfo(
          svcUser = response.svcUser,
          svcParty = svc,
        )
      }
  }

  case class SetConfigSchedule(configSchedule: Schedule[Instant, CoinConfig[USD]])
      extends BaseCommand[SetConfigScheduleRequest, Empty, Unit] {

    override def submitRequest(
        service: SvcServiceStub,
        request: SetConfigScheduleRequest,
    ): Future[Empty] = service.setConfigSchedule(request)

    override def createRequest(): Either[String, SetConfigScheduleRequest] = {
      val scheduleJproto = configSchedule.toValue(
        Timestamp.fromInstant,
        _.toValue(_.toValue),
      )
      val record: scalaValue.Record = scalaValue.Record.fromJavaProto(scheduleJproto.toProtoRecord)
      Right(
        SetConfigScheduleRequest(Some(record))
      )
    }

    override def handleResponse(response: Empty): Either[String, Unit] = Right(())
  }
}
