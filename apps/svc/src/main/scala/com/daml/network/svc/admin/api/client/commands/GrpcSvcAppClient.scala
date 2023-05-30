package com.daml.network.svc.admin.api.client.commands

import com.daml.ledger.api.v1.value as scalaValue
import com.daml.ledger.javaapi.data.Timestamp
import com.daml.network.codegen.java.cc.coin.FeaturedAppRight
import com.daml.network.codegen.java.cc.coinconfig.{CoinConfig, USD}
import com.daml.network.codegen.java.cc.schedule.Schedule
import com.daml.network.svc.v0
import com.daml.network.svc.v0.{
  GetDebugInfoResponse,
  GrantFeaturedAppRightRequest,
  GrantFeaturedAppRightResponse,
  JoinCollectiveRequest,
  SetConfigScheduleRequest,
  WithdrawFeaturedAppRightRequest,
}
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

  case class GrantFeaturedAppRight(provider: PartyId)
      extends BaseCommand[
        GrantFeaturedAppRightRequest,
        GrantFeaturedAppRightResponse,
        FeaturedAppRight.ContractId,
      ] {

    override def submitRequest(
        service: SvcServiceStub,
        request: GrantFeaturedAppRightRequest,
    ): Future[GrantFeaturedAppRightResponse] = service.grantFeaturedAppRight(request)

    override def createRequest(): Either[String, GrantFeaturedAppRightRequest] = Right(
      GrantFeaturedAppRightRequest(Codec.encode(provider))
    )

    override def handleResponse(
        response: GrantFeaturedAppRightResponse
    ): Either[String, FeaturedAppRight.ContractId] =
      Codec.decodeJavaContractId(FeaturedAppRight.COMPANION)(response.featuredAppRightContractId)
  }

  case class WithdrawFeaturedAppRight(provider: PartyId)
      extends BaseCommand[
        WithdrawFeaturedAppRightRequest,
        Empty,
        Unit,
      ] {

    override def submitRequest(
        service: SvcServiceStub,
        request: WithdrawFeaturedAppRightRequest,
    ): Future[Empty] = service.withdrawFeaturedAppRight(request)

    override def createRequest(): Either[String, WithdrawFeaturedAppRightRequest] = Right(
      WithdrawFeaturedAppRightRequest(Codec.encode(provider))
    )

    /** Handle the response the service has provided
      */
    override def handleResponse(response: Empty): Either[String, Unit] = Right(())
  }

  // TODO(#4367) part of mock SVC bootstrap; remove
  case class JoinCollective(svParty: PartyId)
      extends BaseCommand[
        JoinCollectiveRequest,
        Empty,
        Unit,
      ] {

    override def submitRequest(
        service: SvcServiceStub,
        request: JoinCollectiveRequest,
    ): Future[Empty] = service.joinCollective(request)

    override def createRequest(): Either[String, JoinCollectiveRequest] = Right(
      JoinCollectiveRequest(Codec.encode(svParty))
    )

    /** Handle the response the service has provided
      */
    override def handleResponse(response: Empty): Either[String, Unit] = Right(())
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
