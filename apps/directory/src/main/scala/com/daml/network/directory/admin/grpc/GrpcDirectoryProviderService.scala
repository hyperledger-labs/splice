package com.daml.network.directory.admin.grpc

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding.{Primitive}
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.directory.v0
import com.daml.network.directory.v0.DirectoryServiceGrpc
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.{Contract, Proto}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.daml.network.codegen.CN.{Directory => codegen, Wallet => walletCodegen}
import com.daml.network.codegen.DA
import com.daml.network.codegen.DA.Time.Types.RelTime
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcDirectoryService(
    ledgerClient: CoinLedgerClient,
    scanConnection: ScanConnection,
    damlUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends DirectoryServiceGrpc.DirectoryService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcDirectoryService")

  // TODO(M1-92) Make these parameters configurable
  private val entryFee: Primitive.Numeric = 1.0
  private val collectionDuration = RelTime(
    10_000_000
  )
  private val acceptDuration = RelTime(
    60_000_000
  )

  @nowarn("cat=unused")
  override def listInstallRequests(request: Empty): Future[v0.ListInstallRequestsResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for {
        partyId <- connection.getPrimaryParty(damlUser)
        installRequestsLAPI <- connection
          .activeContracts(partyId, codegen.DirectoryInstallRequest)
      } yield {
        val filteredRequests = installRequestsLAPI.filter(contract =>
          PartyId.tryFromPrim(contract.value.provider) == partyId
        )
        v0.ListInstallRequestsResponse(
          filteredRequests.map(r => Contract.fromCodegenContract(r).toProtoV0)
        )
      }
    }

  override def acceptInstallRequest(
      request: v0.AcceptInstallRequestRequest
  ): Future[v0.AcceptInstallRequestResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for {
        partyId <- connection.getPrimaryParty(damlUser)
        svc <- scanConnection.getSvcPartyId()
        arg = codegen.DirectoryInstallRequest_Accept(
          svc = svc.toPrim,
          entryFee = entryFee,
          collectionDuration = collectionDuration,
          acceptDuration = acceptDuration,
        )
        installCid = Proto.tryDecodeContractId[codegen.DirectoryInstallRequest](request.contractId)
        acceptCmd = installCid.exerciseDirectoryInstallRequest_Accept(arg)
        installCid <- connection.submitWithResult(Seq(partyId), Seq(), acceptCmd)
      } yield {
        v0.AcceptInstallRequestResponse(Proto.encode(installCid))
      }
    }

  @nowarn("cat=unused")
  override def listEntryRequests(request: Empty): Future[v0.ListEntryRequestsResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for {
        partyId <- connection.getPrimaryParty(damlUser)
        entryRequestsLAPI <- connection
          .activeContracts(partyId, codegen.DirectoryEntryRequest)
      } yield {
        val filteredRequests = entryRequestsLAPI.filter(contract =>
          PartyId.tryFromPrim(contract.value.entry.provider) == partyId
        )
        v0.ListEntryRequestsResponse(
          filteredRequests.map(r =>
            Contract.fromCodegenContract[codegen.DirectoryEntryRequest](r).toProtoV0
          )
        )
      }
    }

  override def requestEntryPayment(
      request: v0.RequestEntryPaymentRequest
  ): Future[v0.RequestEntryPaymentResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for {
        partyId <- connection.getPrimaryParty(damlUser)
        entryRequest <- connection.fetchByContractId(codegen.DirectoryEntryRequest)(
          partyId,
          Proto.tryDecodeContractId(request.contractId),
        )
        cmd = codegen.DirectoryInstall
          .key(DA.Types.Tuple2(partyId.toPrim, entryRequest.value.entry.user))
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
        partyId <- connection.getPrimaryParty(damlUser)
        acceptedAppPayment <- connection.fetchByContractId(walletCodegen.AcceptedAppPayment)(
          partyId,
          Proto.tryDecodeContractId(request.contractId),
        )
        // TODO(i321) Add uniqueness check
        cmd = codegen.DirectoryInstall
          .key(DA.Types.Tuple2(partyId.toPrim, acceptedAppPayment.value.sender))
          .exerciseDirectoryInstall_CollectEntryPayment(
            codegen.DirectoryInstall_CollectEntryPayment(acceptedAppPayment.contractId)
          )
        entryCid <- connection.submitWithResult(Seq(partyId), Seq(), cmd)
      } yield {
        v0.CollectEntryPaymentResponse(Proto.encode(entryCid))
      }
    }

  @nowarn("cat=unused")
  override def listEntries(request: Empty): Future[v0.ListEntriesResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for {
        partyId <- connection.getPrimaryParty(damlUser)
        entries <- listEntries(partyId)
      } yield v0.ListEntriesResponse(entries.map(_.toProtoV0))
    }

  @nowarn("cat=unused")
  override def lookupEntryByParty(
      request: v0.LookupEntryByPartyRequest
  ): Future[v0.LookupEntryByPartyResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for {
        partyId <- connection.getPrimaryParty(damlUser)
        entries <- listEntries(partyId)
      } yield {
        entries
          .collectFirst {
            case entry if entry.payload.user == ApiTypes.Party(request.user) =>
              entry
          }
          .map(_.toProtoV0)
          .fold(throw new StatusRuntimeException(Status.NOT_FOUND))(e =>
            v0.LookupEntryByPartyResponse(Some(e))
          )
      }
    }

  @nowarn("cat=unused")
  override def lookupEntryByName(
      request: v0.LookupEntryByNameRequest
  ): Future[v0.LookupEntryByNameResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for {
        partyId <- connection.getPrimaryParty(damlUser)
        entries <- listEntries(partyId)
      } yield entries
        .collectFirst {
          case entry if entry.payload.name == request.name =>
            entry
        }
        .map(_.toProtoV0)
        .fold(throw new StatusRuntimeException(Status.NOT_FOUND))(e =>
          v0.LookupEntryByNameResponse(Some(e))
        )
    }

  @nowarn("cat=unused")
  override def getProviderPartyId(request: Empty): Future[v0.GetProviderPartyIdResponse] =
    withSpanFromGrpcContext("GrpcDirectoryService") { implicit traceContext => span =>
      for {
        partyId <- connection.getPrimaryParty(damlUser)
      } yield v0.GetProviderPartyIdResponse(Proto.encode(partyId))
    }

  private def listEntries(party: PartyId): Future[Seq[Contract[codegen.DirectoryEntry]]] =
    for {
      decoded <- connection.activeContracts(party, codegen.DirectoryEntry)
    } yield {
      val filtered =
        decoded.filter(contract => PartyId.tryFromPrim(contract.value.provider) == party)
      filtered.map(Contract.fromCodegenContract[codegen.DirectoryEntry])
    }

}
