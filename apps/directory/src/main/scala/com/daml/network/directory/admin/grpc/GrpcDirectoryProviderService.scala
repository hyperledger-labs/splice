package com.daml.network.directory.admin.grpc

import com.daml.ledger.client.binding.{Primitive, TemplateCompanion}
import com.daml.network.directory.store.DirectoryAppStore
import com.daml.network.directory.v0
import com.daml.network.directory.v0.DirectoryServiceGrpc
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.{Contract, Proto}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcDirectoryService(
    store: DirectoryAppStore,
    ledgerClient: CoinLedgerClient,
    scanConnection: ScanConnection,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends DirectoryServiceGrpc.DirectoryService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcDirectoryService")

  private[this] def fetchContractById[T](
      companion: TemplateCompanion[T]
  )(cid: Primitive.ContractId[T])(implicit ec: ExecutionContext): Future[Contract[T]] =
    store
      .lookupContractById(companion)(cid)
      .map(result =>
        result.value.getOrElse(
          throw new IllegalStateException(
            s"No active contract of template ${companion.id} with contract id $cid"
          )
        )
      )

  @nowarn("cat=unused")
  override def lookupInstall(
      request: v0.LookupInstallRequest
  ): Future[v0.LookupInstallResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for {
        install <- store.lookupInstall(PartyId.tryFromProtoPrimitive(request.userPartyId))
      } yield {
        v0.LookupInstallResponse(install.value.map(_.toProtoV0))
      }
    }

  @nowarn("cat=unused")
  override def listEntries(request: Empty): Future[v0.ListEntriesResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for { entries <- store.listEntries() } yield v0.ListEntriesResponse(
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
