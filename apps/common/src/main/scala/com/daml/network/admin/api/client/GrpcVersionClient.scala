package com.daml.network.admin.api.client.version

import com.daml.network.v0
import com.daml.network.v0.GetVersionResponse
import com.daml.network.v0.VersionServiceGrpc.VersionServiceStub
import com.digitalasset.canton.ProtoDeserializationError.FieldNotSet
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.serialization.ProtoConverter
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import java.time.Instant
import scala.concurrent.Future
import cats.implicits.*

object GrpcVersionClient {
  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = VersionServiceStub

    override def createService(channel: ManagedChannel): VersionServiceStub =
      v0.VersionServiceGrpc.stub(channel)
  }

  case class VersionInfo(version: String, commitTs: Instant)

  case class GetVersion() extends BaseCommand[Empty, v0.GetVersionResponse, VersionInfo] {

    override def createRequest(): Either[String, Empty] = Right(Empty())

    override def submitRequest(
        service: VersionServiceStub,
        request: Empty,
    ): Future[GetVersionResponse] = service.getVersion(request)

    override def handleResponse(response: GetVersionResponse): Either[String, VersionInfo] = {
      (for {
        protoCommitTs <- response.commitTs.toRight(FieldNotSet("commitTs"))
        parsedCommitTs <- ProtoConverter.InstantConverter.fromProtoPrimitive(protoCommitTs)
      } yield VersionInfo(response.version, parsedCommitTs)).leftMap(_.toString)
    }
  }
}
