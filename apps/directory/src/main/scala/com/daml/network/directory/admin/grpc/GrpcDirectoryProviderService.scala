package com.daml.network.directory.admin.grpc

import com.daml.ledger.client.binding.{Primitive, TemplateCompanion}
import com.daml.network.codegen.CN.{Directory => codegen, Wallet => walletCodegen}
import com.daml.network.codegen.DA
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
  override def listEntryRequests(request: Empty): Future[v0.ListEntryRequestsResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for { reqs <- store.listEntryRequests() } yield {
        v0.ListEntryRequestsResponse(reqs.value.map(_.toProtoV0))
      }
    }

  override def requestEntryPayment(
      request: v0.RequestEntryPaymentRequest
  ): Future[v0.RequestEntryPaymentResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for {
        partyId <- store.getProviderParty()
        entryRequest <- fetchContractById(codegen.DirectoryEntryRequest)(
          Proto.tryDecodeContractId(request.contractId)
        )
        cmd = codegen.DirectoryInstall
          .key(DA.Types.Tuple2(partyId.toPrim, entryRequest.payload.entry.user))
          .exerciseDirectoryInstall_RequestEntryPayment(
            codegen.DirectoryInstall_RequestEntryPayment(entryRequest.contractId)
          )
        requestCid <- connection.submitWithResult(Seq(partyId), Seq(), cmd)
      } yield {
        v0.RequestEntryPaymentResponse(Proto.encode(requestCid))
      }
    }

  override def collectEntryPayment(
      request: v0.CollectEntryPaymentRequest
  ): Future[v0.CollectEntryPaymentResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for {
        partyId <- store.getProviderParty()
        acceptedAppPayment <- fetchContractById(walletCodegen.AcceptedAppPayment)(
          Proto.tryDecodeContractId(request.contractId)
        )
        // TODO(i321) Add uniqueness check
        cmd = codegen.DirectoryInstall
          .key(DA.Types.Tuple2(partyId.toPrim, acceptedAppPayment.payload.sender))
          .exerciseDirectoryInstall_CollectEntryPayment(
            codegen.DirectoryInstall_CollectEntryPayment(acceptedAppPayment.contractId)
          )
        result <- connection.submitWithResult(Seq(partyId), Seq(), cmd)
      } yield {
        v0.CollectEntryPaymentResponse(Proto.encode(result._1))
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
      for { partyId <- store.getProviderParty() } yield v0.GetProviderPartyIdResponse(
        Proto.encode(partyId)
      )
    }

}
