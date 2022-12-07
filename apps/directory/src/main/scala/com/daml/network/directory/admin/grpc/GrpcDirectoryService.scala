package com.daml.network.directory.admin.grpc

import com.daml.network.directory.store.DirectoryStore
import com.daml.network.directory.v0
import com.daml.network.directory.v0.DirectoryServiceGrpc
import com.daml.network.util.Proto
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcDirectoryService(
    store: DirectoryStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends DirectoryServiceGrpc.DirectoryService
    with Spanning
    with NamedLogging {

  @nowarn("cat=unused")
  override def listEntries(request: Empty): Future[v0.ListEntriesResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for { entries <- store.acs.listContracts(directoryCodegen.DirectoryEntry.COMPANION) } yield v0
        .ListEntriesResponse(
          entries.value.map(_.toProtoV0)
        )
    }

  @nowarn("cat=unused")
  override def lookupEntryByParty(
      request: v0.LookupEntryByPartyRequest
  ): Future[v0.LookupEntryByPartyResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for {
        entry <- store.lookupEntryByParty(PartyId.tryFromProtoPrimitive(request.user))
      } yield {
        entry.value
          .fold(throw new StatusRuntimeException(Status.NOT_FOUND))(e =>
            v0.LookupEntryByPartyResponse(Some(e.toProtoV0))
          )
      }
    }

  @nowarn("cat=unused")
  override def lookupEntryByName(
      request: v0.LookupEntryByNameRequest
  ): Future[v0.LookupEntryByNameResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for {
        entry <- store.lookupEntryByName(request.name)
      } yield {
        entry.value
          .fold(throw new StatusRuntimeException(Status.NOT_FOUND))(e =>
            v0.LookupEntryByNameResponse(Some(e.toProtoV0))
          )
      }
    }

  @nowarn("cat=unused")
  override def getProviderPartyId(request: Empty): Future[v0.GetProviderPartyIdResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      Future.successful(
        v0.GetProviderPartyIdResponse(
          Proto.encode(store.providerParty)
        )
      )
    }

}
