package com.daml.network.scan.admin.api.client.commands

import cats.instances.either.*
import cats.syntax.either.*
import cats.syntax.traverse.*
import com.daml.ledger.api.v1.transaction.TransactionTree as ScalaTransactionTree
import com.daml.ledger.javaapi.data.TransactionTree
import com.daml.network.codegen.java.cc.coin.{CoinRules, FeaturedAppRight}
import com.daml.network.codegen.java.cc.round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.codegen.java.cc.{coin as coinCodegen, round as roundCodegen}
import com.daml.network.scan.v0
import com.daml.network.scan.v0.ScanServiceGrpc.ScanServiceStub
import com.daml.network.scan.v0.{GetClosedRoundsResponse, ListFeaturedAppRightsResponse}
import com.daml.network.util.{JavaContract, Proto}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.google.protobuf.empty.Empty
import io.grpc.ManagedChannel

import scala.concurrent.Future

object GrpcScanAppClient {

  abstract class BaseCommand[Req, Res, Result] extends GrpcAdminCommand[Req, Res, Result] {
    override type Svc = ScanServiceStub

    override def createService(channel: ManagedChannel): ScanServiceStub =
      v0.ScanServiceGrpc.stub(channel)
  }

  final case class GetSvcPartyId() extends BaseCommand[Empty, v0.GetSvcPartyIdResponse, PartyId] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: ScanServiceStub,
        req: Empty,
    ): Future[v0.GetSvcPartyIdResponse] =
      service.getSvcPartyId(req)

    override def handleResponse(response: v0.GetSvcPartyIdResponse): Either[String, PartyId] =
      PartyId.fromProtoPrimitive(response.svcPartyId)
  }

  case class TransferContext(
      coinRules: Option[
        JavaContract[coinCodegen.CoinRules.ContractId, coinCodegen.CoinRules]
      ],
      latestOpenMiningRound: Option[
        JavaContract[roundCodegen.OpenMiningRound.ContractId, roundCodegen.OpenMiningRound]
      ],
      openMiningRounds: Seq[
        JavaContract[roundCodegen.OpenMiningRound.ContractId, roundCodegen.OpenMiningRound]
      ],
  )

  final case class GetTransferContext()
      extends BaseCommand[Empty, v0.GetTransferContextResponse, TransferContext] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: ScanServiceStub,
        req: Empty,
    ): Future[v0.GetTransferContextResponse] =
      service.getTransferContext(req)

    override def handleResponse(
        response: v0.GetTransferContextResponse
    ): Either[String, TransferContext] =
      for {
        coinRules <- response.coinRules
          .traverse(coinRules => JavaContract.fromProto(coinCodegen.CoinRules.COMPANION)(coinRules))
          .leftMap(_.toString)
        openMiningRounds <- response.openMiningRounds
          .traverse(round => JavaContract.fromProto(roundCodegen.OpenMiningRound.COMPANION)(round))
          .leftMap(_.toString)
        latestOpenMiningRound <- response.latestOpenMiningRound
          .traverse(round => JavaContract.fromProto(roundCodegen.OpenMiningRound.COMPANION)(round))
          .leftMap(_.toString)
      } yield TransferContext(
        coinRules,
        latestOpenMiningRound,
        openMiningRounds,
      )
  }

  final case class GetLatestOpenAndIssuingMiningRounds()
      extends BaseCommand[
        Empty,
        v0.GetLatestOpenAndIssuingMiningRoundsResponse,
        (
            JavaContract[OpenMiningRound.ContractId, OpenMiningRound],
            Seq[JavaContract[IssuingMiningRound.ContractId, IssuingMiningRound]],
        ),
      ] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: ScanServiceStub,
        req: Empty,
    ): Future[v0.GetLatestOpenAndIssuingMiningRoundsResponse] =
      service.getLatestOpenAndIssuingMiningRounds(req)

    override def handleResponse(
        response: v0.GetLatestOpenAndIssuingMiningRoundsResponse
    ): Either[
      String,
      (
          JavaContract[OpenMiningRound.ContractId, OpenMiningRound],
          Seq[JavaContract[IssuingMiningRound.ContractId, IssuingMiningRound]],
      ),
    ] =
      for {
        issuingMiningRounds <- response.issuingMiningRounds
          .traverse(round =>
            JavaContract.fromProto(roundCodegen.IssuingMiningRound.COMPANION)(round)
          )
          .leftMap(_.toString)
        latestOpenMiningRoundO <- response.latestOpenMiningRound
          .traverse(round => JavaContract.fromProto(roundCodegen.OpenMiningRound.COMPANION)(round))
          .leftMap(_.toString)
        latestOpenMiningRound <- latestOpenMiningRoundO.toRight("found no open OpenMiningRound")
      } yield (latestOpenMiningRound, issuingMiningRounds)
  }

  final case class GetCoinRules()
      extends BaseCommand[
        Empty,
        v0.GetCoinRulesResponse,
        JavaContract[CoinRules.ContractId, CoinRules],
      ] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: ScanServiceStub,
        req: Empty,
    ): Future[v0.GetCoinRulesResponse] =
      service.getCoinRules(req)

    override def handleResponse(
        response: v0.GetCoinRulesResponse
    ): Either[
      String,
      JavaContract[CoinRules.ContractId, CoinRules],
    ] =
      for {
        coinRulesO <- response.coinRules
          .traverse(rules => JavaContract.fromProto(coinCodegen.CoinRules.COMPANION)(rules))
          .leftMap(_.toString)
        coinRules <- coinRulesO.toRight("found no coin rules")
      } yield coinRules
  }

  final case class GetCoinTransactionDetails(transactionId: String)
      extends BaseCommand[
        v0.GetCoinTransactionDetailsRequest,
        v0.GetCoinTransactionDetailsResponse,
        TransactionTree,
      ] {
    override def createRequest(): Either[String, v0.GetCoinTransactionDetailsRequest] =
      Right(v0.GetCoinTransactionDetailsRequest(transactionId))

    override def submitRequest(
        service: ScanServiceStub,
        req: v0.GetCoinTransactionDetailsRequest,
    ): Future[v0.GetCoinTransactionDetailsResponse] =
      service.getCoinTransactionDetails(req)

    override def handleResponse(
        response: v0.GetCoinTransactionDetailsResponse
    ): Either[String, TransactionTree] =
      response.tree
        .toRight(
          "received no transaction tree in the GetCoinTransactionDetailsResponse response from the server"
        )
        .map(t => TransactionTree.fromProto(ScalaTransactionTree.toJavaProto(t)))
  }

  final case class GetClosedRounds()
      extends BaseCommand[
        Empty,
        v0.GetClosedRoundsResponse,
        Seq[JavaContract[roundCodegen.ClosedMiningRound.ContractId, roundCodegen.ClosedMiningRound]],
      ] {

    override def createRequest(): Either[String, Empty] = Right(Empty())

    override def submitRequest(
        service: ScanServiceStub,
        req: Empty,
    ): Future[GetClosedRoundsResponse] = service.getClosedRounds(req)

    override def handleResponse(
        response: GetClosedRoundsResponse
    ): Either[String, Seq[
      JavaContract[roundCodegen.ClosedMiningRound.ContractId, roundCodegen.ClosedMiningRound]
    ]] = {
      response.rounds
        .traverse(round => JavaContract.fromProto(roundCodegen.ClosedMiningRound.COMPANION)(round))
        .leftMap(_.toString)
    }
  }

  case class ListFeaturedAppRight()
      extends BaseCommand[
        Empty,
        v0.ListFeaturedAppRightsResponse,
        Seq[JavaContract[FeaturedAppRight.ContractId, FeaturedAppRight]],
      ] {

    override def submitRequest(
        service: ScanServiceStub,
        request: Empty,
    ): Future[ListFeaturedAppRightsResponse] = service.listFeaturedAppRights(request)

    override def createRequest(): Either[String, Empty] = Right(Empty())

    override def handleResponse(
        response: ListFeaturedAppRightsResponse
    ): Either[String, Seq[JavaContract[FeaturedAppRight.ContractId, FeaturedAppRight]]] =
      response.featuredApps
        .traverse(co => JavaContract.fromProto(FeaturedAppRight.COMPANION)(co))
        .leftMap(_.toString)
  }

  case class LookupFeaturedAppRight(providerPartyId: PartyId)
      extends BaseCommand[
        v0.LookupFeaturedAppRightRequest,
        v0.LookupFeaturedAppRightResponse,
        Option[JavaContract[FeaturedAppRight.ContractId, FeaturedAppRight]],
      ] {

    override def submitRequest(
        service: ScanServiceStub,
        request: v0.LookupFeaturedAppRightRequest,
    ): Future[v0.LookupFeaturedAppRightResponse] = service.lookupFeaturedAppRight(request)

    override def createRequest(): Either[String, v0.LookupFeaturedAppRightRequest] = Right(
      v0.LookupFeaturedAppRightRequest(Proto.encode(providerPartyId))
    )

    override def handleResponse(
        response: v0.LookupFeaturedAppRightResponse
    ): Either[String, Option[JavaContract[FeaturedAppRight.ContractId, FeaturedAppRight]]] =
      response.featuredAppRight
        .traverse(co => JavaContract.fromProto(FeaturedAppRight.COMPANION)(co))
        .leftMap(_.toString)
  }

  case class ListConnectedDomains(
  ) extends BaseCommand[Empty, v0.ListConnectedDomainsResponse, Map[DomainAlias, DomainId]] {
    override def createRequest(): Either[String, Empty] =
      Right(Empty())

    override def submitRequest(
        service: ScanServiceStub,
        request: Empty,
    ): Future[v0.ListConnectedDomainsResponse] = service.listConnectedDomains(request)

    override def handleResponse(
        response: v0.ListConnectedDomainsResponse
    ): Either[String, Map[DomainAlias, DomainId]] =
      Proto.decode(Proto.ConnectedDomains)(response.getDomains)
  }
}
