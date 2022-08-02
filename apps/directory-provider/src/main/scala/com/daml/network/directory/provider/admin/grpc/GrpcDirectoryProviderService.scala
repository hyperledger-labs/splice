package com.daml.network.directory.provider.admin.grpc

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.transaction_filter
import com.daml.ledger.api.v1.transaction_filter.{Filters, InclusiveFilters, TransactionFilter}
import com.daml.ledger.api.v1.value.Identifier
import com.daml.ledger.client.binding.{Contract => CodegenContract, Primitive, TemplateCompanion}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.directory_provider.v0
import com.daml.network.directory_provider.v0.DirectoryProviderServiceGrpc
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.{Contract, CoinUtil}
import com.digitalasset.canton.ledger.api.client.DecodeUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.network.CC.Coin.Coin
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import com.digitalasset.network.CN.{Directory => codegen, Wallet => walletCodegen}
import com.digitalasset.network.DA
import com.digitalasset.network.DA.Time.Types.RelTime

import scala.annotation.nowarn
import scala.language.implicitConversions
import scala.concurrent.{ExecutionContext, Future}

class GrpcDirectoryProviderService(
    connection: CoinLedgerConnection,
    scanConnection: ScanConnection,
    damlUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    @nowarn("cat=unused")
    ec: ExecutionContext,
    tracer: Tracer,
) extends DirectoryProviderServiceGrpc.DirectoryProviderService
    with Spanning
    with NamedLogging {

  // TODO (MK) Make these parameters configurable
  private val entryFee: Primitive.Numeric = 1.0
  private val collectionDuration = RelTime(
    10_000_000
  )
  private val approveDuration = RelTime(
    60_000_000
  )

  private def getParty() =
    for {
      partyO <- connection.getUser(damlUser)
      party = partyO.getOrElse(
        sys.error(s"Unable to find party for user $damlUser")
      )
    } yield party

  @nowarn("cat=unused")
  override def listInstallRequests(request: Empty): Future[v0.ListInstallRequestsResponse] =
    withSpanFromGrpcContext("GrpcDirectoryProviderService") { implicit traceContext => span =>
      for {
        partyId <- getParty()
        activeContracts <- connection.activeContracts(
          CoinLedgerConnection.transactionFilter(partyId, codegen.DirectoryInstallRequest.id)
        )
        installRequestsLAPI = activeContracts._1.flatMap(event =>
          DecodeUtil.decodeCreated(codegen.DirectoryInstallRequest)(event)
        )
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
    withSpanFromGrpcContext("GrpcDirectoryProviderService") { implicit traceContext => span =>
      for {
        partyId <- getParty()
        svc <- scanConnection.getSvcPartyId()
        arg = codegen.DirectoryInstallRequest_Accept(
          svc = svc.toPrim,
          entryFee = entryFee,
          collectionDuration = collectionDuration,
          approveDuration = approveDuration,
        )
        acceptCmd = Primitive.ContractId
          .apply[codegen.DirectoryInstallRequest](request.contractId)
          .exerciseDirectoryInstallRequest_Accept(partyId.toPrim, arg)
          .command
        tx <- connection.submitCommand(Seq(partyId), Seq(), Seq(acceptCmd))
        installs = DecodeUtil.decodeAllCreated(codegen.DirectoryInstall)(tx.getTransaction)
        _ = require(
          installs.length == 1,
          s"Expected accept to create only one install contract but found ${installs.length} installs $installs",
        )
      } yield {
        v0.AcceptInstallRequestResponse(installs(0).contractId.toString)
      }
    }

  @nowarn("cat=unused")
  override def listEntryRequests(request: Empty): Future[v0.ListEntryRequestsResponse] =
    withSpanFromGrpcContext("GrpcDirectoryProviderService") { implicit traceContext => span =>
      for {
        partyId <- getParty()
        activeContracts <- connection.activeContracts(
          CoinLedgerConnection.transactionFilter(partyId, codegen.DirectoryEntryRequest.id)
        )
        entryRequestsLAPI = activeContracts._1.flatMap(event =>
          DecodeUtil.decodeCreated(codegen.DirectoryEntryRequest)(event)
        )
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
    withSpanFromGrpcContext("GrpcDirectoryProviderService") { implicit traceContext => span =>
      for {
        partyId <- getParty()
        entryRequest <- fetchByContractId(codegen.DirectoryEntryRequest)(
          partyId,
          Primitive.ContractId(request.contractId),
        )
        cmd = codegen.DirectoryInstall
          .key(DA.Types.Tuple2(partyId.toPrim, entryRequest.value.entry.user))
          .exerciseDirectoryInstall_RequestEntryPayment(
            partyId.toPrim,
            codegen.DirectoryInstall_RequestEntryPayment(entryRequest.contractId),
          )
          .command
        tx <- connection.submitCommand(Seq(partyId), Seq(), Seq(cmd))
        requests = DecodeUtil.decodeAllCreated(walletCodegen.PaymentRequest)(
          tx.getTransaction
        )
        _ = require(
          requests.length == 1,
          s"Expected requestEntryPayment to create only one payment request contract but found ${requests.length} requests $requests",
        )
      } yield {
        v0.RequestEntryPaymentResponse(ApiTypes.ContractId.unwrap(requests(0).contractId))
      }
    }

  override def collectEntryPayment(
      request: v0.CollectEntryPaymentRequest
  ): Future[v0.CollectEntryPaymentResponse] =
    withSpanFromGrpcContext("GrpcDirectoryProviderService") { implicit traceContext => span =>
      for {
        partyId <- getParty()
        approvedPayment <- fetchByContractId(walletCodegen.ApprovedPayment)(
          partyId,
          Primitive.ContractId(request.contractId),
        )
        // TODO(i321) Add uniqueness check
        cmd = codegen.DirectoryInstall
          .key(DA.Types.Tuple2(partyId.toPrim, approvedPayment.value.payer))
          .exerciseDirectoryInstall_CollectEntryPayment(
            partyId.toPrim,
            codegen.DirectoryInstall_CollectEntryPayment(approvedPayment.contractId),
          )
          .command
        tx <- connection.submitCommand(Seq(partyId), Seq(), Seq(cmd))
        entries = DecodeUtil.decodeAllCreated(codegen.DirectoryEntry)(
          tx.getTransaction
        )
        _ = require(
          entries.length == 1,
          s"Expected collectEntryPayment to create only one entryt contract but found ${entries.length} requests $entries",
        )
      } yield {
        v0.CollectEntryPaymentResponse(ApiTypes.ContractId.unwrap(entries(0).contractId))
      }
    }

  @nowarn("cat=unused")
  override def listEntries(request: Empty): Future[v0.ListEntriesResponse] =
    withSpanFromGrpcContext("GrpcDirectoryProviderService") { implicit traceContext => span =>
      for {
        partyId <- getParty()
        entries <- listEntries(partyId)
      } yield v0.ListEntriesResponse(entries.map(_.toProtoV0))
    }

  @nowarn("cat=unused")
  override def lookupEntryByParty(
      request: v0.LookupEntryByPartyRequest
  ): Future[v0.LookupEntryResponse] =
    withSpanFromGrpcContext("GrpcDirectoryProviderService") { implicit traceContext => span =>
      for {
        partyId <- getParty()
        entries <- listEntries(partyId)
      } yield {
        entries
          .collectFirst {
            case entry if entry.payload.user == ApiTypes.Party(request.user) =>
              entry
          }
          .map(_.toProtoV0)
          .fold(throw new StatusRuntimeException(Status.NOT_FOUND))(e =>
            v0.LookupEntryResponse(Some(e))
          )
      }
    }

  @nowarn("cat=unused")
  override def lookupEntryByName(
      request: v0.LookupEntryByNameRequest
  ): Future[v0.LookupEntryResponse] =
    withSpanFromGrpcContext("GrpcDirectoryProviderService") { implicit traceContext => span =>
      for {
        partyId <- getParty()
        entries <- listEntries(partyId)
      } yield entries
        .collectFirst {
          case entry if entry.payload.name == request.name =>
            entry
        }
        .map(_.toProtoV0)
        .fold(throw new StatusRuntimeException(Status.NOT_FOUND))(e =>
          v0.LookupEntryResponse(Some(e))
        )
    }

  @nowarn("cat=unused")
  override def getProviderPartyId(request: Empty): Future[v0.GetProviderPartyIdResponse] =
    withSpanFromGrpcContext("GrpcDirectoryProviderService") { implicit traceContext => span =>
      for {
        partyId <- getParty()
      } yield v0.GetProviderPartyIdResponse(partyId.toProtoPrimitive)
    }

  private def listEntries(party: PartyId): Future[Seq[Contract[codegen.DirectoryEntry]]] =
    for {
      contracts <- connection.activeContracts(
        txFilter(party, ApiTypes.TemplateId.unwrap(codegen.DirectoryEntry.id))
      )
      decoded = contracts._1.flatMap(event =>
        DecodeUtil.decodeCreated(codegen.DirectoryEntry)(event)
      )
    } yield {
      val filtered =
        decoded.filter(contract => PartyId.tryFromPrim(contract.value.provider) == party)
      filtered.map(Contract.fromCodegenContract[codegen.DirectoryEntry](_))
    }

  private def txFilter(partyId: PartyId, tplId: Identifier): TransactionFilter = {
    transaction_filter.TransactionFilter(
      Map(
        partyId.toPrim.toString -> Filters(
          Some(
            InclusiveFilters(templateIds = Seq(tplId))
          )
        )
      )
    )
  }

  private def fetchByContractId[T](
      companion: TemplateCompanion[T]
  )(partyId: PartyId, cid: Primitive.ContractId[T]): Future[CodegenContract[T]] = {
    for {
      contracts <- connection.activeContracts(
        CoinLedgerConnection.transactionFilter(partyId, companion.id)
      )
      decoded = contracts._1.flatMap(event => DecodeUtil.decodeCreated(companion)(event))
    } yield {
      decoded
        .collectFirst {
          case contract if contract.contractId == cid => contract
        }
        .getOrElse(
          throw new IllegalStateException(
            s"No active contract of template ${companion.id} with contract id $cid"
          )
        )
    }
  }

}
