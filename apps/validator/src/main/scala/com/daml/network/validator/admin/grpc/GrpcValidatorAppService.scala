package com.daml.network.validator.admin.grpc

import com.daml.ledger.api.v1.command_service.SubmitAndWaitForTransactionResponse
import com.daml.network.codegen.CC.CoinRules.CoinRulesRequest
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.{CoinUtil, Proto}
import com.daml.network.validator.store.ValidatorAppStore
import com.daml.network.validator.v0._
import com.daml.network.wallet.util.WalletUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class GrpcValidatorAppService(
    ledgerClient: CoinLedgerClient,
    scanConnection: ScanConnection,
    store: ValidatorAppStore,
    validatorUserName: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ValidatorAppServiceGrpc.ValidatorAppService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcValidatorAppService")

  override def initialize(request: Empty): Future[InitializeResponse] =
    withSpanFromGrpcContext("GrpcValidatorAppService") { implicit traceContext => span =>
      span.setAttribute("username", validatorUserName)

      def createRulesRequestAndUserHostedAtContracts(
          svcParty: PartyId,
          validatorParty: PartyId,
      ): Future[SubmitAndWaitForTransactionResponse] = {
        val coinRulesReq = CoinRulesRequest(user = validatorParty.toPrim, svc = svcParty.toPrim)
        connection
          .submitCommand(
            actAs = Seq(validatorParty),
            readAs = Seq(validatorParty),
            command = Seq(coinRulesReq.create.command),
          )
          .flatMap { _ =>
            CoinUtil.ExplicitDisclosureWorkaround.recordUserHostedAt(
              validatorParty,
              validatorParty,
              connection,
            )
          }
      }

      for {
        _ <- connection.uploadDarFile(CoinUtil) // TODO(i353) move away from dar upload during init
        validatorParty <- connection.createPartyAndUser(validatorUserName)
        svcParty <- scanConnection.getSvcPartyId()
        _ <- createRulesRequestAndUserHostedAtContracts(svcParty, validatorParty)
        _ <- store.setValidatorParty(validatorParty)
        _ <- store.setSvcParty(svcParty)
      } yield InitializeResponse(Proto.encode(validatorParty))
    }

  override def onboardUser(request: OnboardUserRequest): Future[OnboardUserResponse] =
    withSpanFromGrpcContext("GrpcValidatorAppService") { implicit traceContext => span =>
      val name = request.name

      span.setAttribute("name", name)

      for {
        validatorPartyIdMaybe <- store.getValidatorParty()
        validatorPartyId <- validatorPartyIdMaybe.fold[Future[PartyId]] {
          Future.failed(
            new Error("Validator party not set. Did you forget to call `setupValidator`?")
          )
        }(Future.successful)
        userPartyId <- connection.createPartyAndUser(name)
        svcPartyId <- scanConnection.getSvcPartyId()
        _ <- CoinUtil.ExplicitDisclosureWorkaround.recordUserHostedAt(
          userPartyId,
          validatorPartyId,
          connection,
        )
        _ <- WalletUtil.installWalletForUser(
          endUserParty = userPartyId,
          validatorServiceParty = validatorPartyId,
          svcParty = svcPartyId,
          connection = connection,
          logger = logger,
        )
        // Workaround for the lack of "act-as-any-party" rights
        _ <- connection.grantUserRights(validatorUserName, Seq(userPartyId), Seq.empty)
      } yield OnboardUserResponse(Proto.encode(userPartyId))
    }
}
