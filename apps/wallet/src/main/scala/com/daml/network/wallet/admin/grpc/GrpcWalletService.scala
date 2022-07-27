package com.daml.network.wallet.admin.grpc

import java.util.concurrent.atomic.AtomicReference

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.commands.Command
import com.daml.ledger.api.v1.transaction_filter
import com.daml.ledger.api.v1.transaction_filter.{Filters, InclusiveFilters, TransactionFilter}
import com.daml.ledger.api.v1.value.Identifier
import com.daml.ledger.client.binding.{Primitive, Template}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.{InitializeRequest, InitializeResponse, WalletServiceGrpc}
import com.daml.network.util.CoinUtil
import com.daml.network.wallet.CantonCoin
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.ledger.api.client.{DecodeUtil, LedgerConnection}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.network.CC.Coin.Coin
import com.digitalasset.network.CC.CoinRules.CoinRules
import com.digitalasset.network.`Package IDs`
import io.opentelemetry.api.trace.Tracer
import com.digitalasset.network.DA
import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcWalletService(
    connection: CoinLedgerConnection,
    walletDamlUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    @nowarn("cat=unused")
    ec: ExecutionContext,
    tracer: Tracer,
) extends WalletServiceGrpc.WalletService
    with Spanning
    with NamedLogging {

  // TODO(Arne): remove once we have a CC Scan app.
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  val svcParty: AtomicReference[PartyId] = new AtomicReference[PartyId](null)
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  val validatorParty: AtomicReference[PartyId] = new AtomicReference[PartyId](null)

  override def list(request: v0.ListRequest): Future[v0.ListResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        partyIdO <- connection.getUser(walletDamlUser)
        partyId = partyIdO.getOrElse(sys.error(s"Unable to find party for user $walletDamlUser"))
        _ = logger.info(s"received partyid for user testuser: $partyId")
        activeContractsRes <- connection.activeContracts(construct_list_filter(partyId))
        coinsLAPI = activeContractsRes._1.flatMap(event => DecodeUtil.decodeCreated(Coin)(event))

      } yield {
        // TODO(i207): persist response to store
        val coinsProto = coinsLAPI.map(x => CantonCoin.fromContract(x).toProtoV0)
        v0.ListResponse(coinsProto)
      }
    }

  override def tap(request: v0.TapRequest): Future[v0.TapResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletPartyO <- connection.getUser(walletDamlUser)
        walletParty = walletPartyO.getOrElse(
          sys.error(s"Unable to find party for user $walletDamlUser")
        )
        tapCmd = CoinRules
          .key(DA.Types.Tuple2(svcParty.get.toPrim, validatorParty.get.toPrim))
          .exerciseTap(
            walletParty.toPrim,
            walletParty.toPrim,
            BigDecimal(request.amount),
          )
          .command
        tx <- connection.submitCommand(Seq(walletParty), Seq(validatorParty.get()), Seq(tapCmd))
        coins = DecodeUtil.decodeAllCreated(Coin)(tx.getTransaction)
        _ = require(
          coins.length == 1,
          s"Expected tap to create only one coin but found ${coins.length} coins: $coins",
        )
      } yield v0.TapResponse(coins(0).contractId.toString)
    }

  private def construct_list_filter(partyId: PartyId): TransactionFilter = {
    transaction_filter.TransactionFilter(
      Map(
        partyId.toPrim.toString -> Filters(
          Some(
            InclusiveFilters(templateIds = Seq(CoinUtil.coinTemplateId))
          )
        )
      )
    )
  }

  override def initialize(request: InitializeRequest): Future[InitializeResponse] = {
    svcParty.set(
      PartyId.tryFromProtoPrimitive(request.svc.getOrElse(sys.error("svc party not set")))
    )
    validatorParty.set(
      PartyId.tryFromProtoPrimitive(request.validator.getOrElse(sys.error("svc party not set")))
    )
    Future.successful(v0.InitializeResponse())
  }
}
