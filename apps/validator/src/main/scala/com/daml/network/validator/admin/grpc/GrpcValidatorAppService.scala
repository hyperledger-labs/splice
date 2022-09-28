package com.daml.network.validator.admin.grpc

import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.{CoinUtil, Proto}
import com.daml.network.validator.store.ValidatorAppStore
import com.daml.network.validator.util.ValidatorUtil
import com.daml.network.validator.v0._
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class GrpcValidatorAppService(
    ledgerClient: CoinLedgerClient,
    scanConnection: ScanConnection,
    store: ValidatorAppStore,
    validatorUserName: String,
    walletServiceUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ValidatorAppServiceGrpc.ValidatorAppService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcValidatorAppService")

  // TODO(#878) Drop explicit initialize
  override def initialize(request: Empty): Future[InitializeResponse] =
    withSpanFromGrpcContext("GrpcValidatorAppService") { _ => span =>
      span.setAttribute("username", validatorUserName)
      for {
        validatorParty <- connection.getPrimaryParty(validatorUserName)
      } yield InitializeResponse(Proto.encode(validatorParty))
    }

  override def onboardUser(request: OnboardUserRequest): Future[OnboardUserResponse] =
    withSpanFromGrpcContext("GrpcValidatorAppService") { implicit traceContext => span =>
      val name = request.name

      span.setAttribute("name", name)

      for {
        validatorPartyId <- store.getValidatorParty()
        userPartyId <- connection.createPartyAndUser(name)
        svcPartyId <- scanConnection.getSvcPartyId()
        _ <- CoinUtil.ExplicitDisclosureWorkaround.recordUserHostedAt(
          userPartyId,
          validatorPartyId,
          connection,
        )
        walletServiceParty <- store.getWalletServiceParty()
        // Workaround for the lack of "act-as-any-party" rights
        _ <- connection.grantUserRights(validatorUserName, Seq(userPartyId), Seq.empty)
        _ <- ValidatorUtil.installWalletForUser(
          endUserParty = userPartyId,
          walletServiceUser = walletServiceUser,
          walletServiceParty = walletServiceParty,
          validatorServiceParty = validatorPartyId,
          svcParty = svcPartyId,
          connection = connection,
          logger = logger,
        )
        // Create validator right contract so validator can collect validator rewards
        _ <- ValidatorUtil.createValidatorRight(
          user = userPartyId,
          validator = validatorPartyId,
          svc = svcPartyId,
          connection = connection,
        )
      } yield OnboardUserResponse(Proto.encode(userPartyId))
    }
}
