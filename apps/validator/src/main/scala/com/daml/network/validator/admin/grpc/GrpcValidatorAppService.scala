package com.daml.network.validator.admin.grpc

import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.util.{CoinUtil, Proto}
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ValidatorUtil
import com.daml.network.validator.v0._
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class GrpcValidatorAppService(
    ledgerClient: CoinLedgerClient,
    store: ValidatorStore,
    validatorUserName: String,
    walletServiceUser: String,
    retryProvider: CoinRetries,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ValidatorAppServiceGrpc.ValidatorAppService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcValidatorAppService")

  override def getValidatorPartyId(request: Empty): Future[GetValidatorPartyIdResponse] =
    withSpanFromGrpcContext("GrpcValidatorAppService") { _ => _ =>
      Future.successful(GetValidatorPartyIdResponse(Proto.encode(store.key.validatorParty)))
    }

  override def onboardUser(request: OnboardUserRequest): Future[OnboardUserResponse] =
    withSpanFromGrpcContext("GrpcValidatorAppService") { implicit traceContext => span =>
      val name = request.name

      span.setAttribute("name", name)

      for {
        userPartyId <- connection.getOrAllocateParty(name)
        _ <- CoinUtil.ExplicitDisclosureWorkaround.recordUserHostedAt(
          userPartyId,
          store.key.validatorParty,
          logger,
          connection,
          retryProvider,
          store.lookupCCUserHostedAtByParty,
        )
        // Workaround for the lack of "act-as-any-party" rights
        _ <- connection.grantUserRights(validatorUserName, Seq(userPartyId), Seq.empty)
        _ <- ValidatorUtil.installWalletForUser(
          endUserParty = userPartyId,
          endUserName = name,
          walletServiceUser = walletServiceUser,
          walletServiceParty = store.key.walletServiceParty,
          validatorServiceParty = store.key.validatorParty,
          svcParty = store.key.svcParty,
          connection = connection,
          store = store,
          retryProvider = retryProvider,
          logger = logger,
        )
        // Create validator right contract so validator can collect validator rewards
        _ <- ValidatorUtil.createValidatorRight(
          user = userPartyId,
          validator = store.key.validatorParty,
          svc = store.key.svcParty,
          connection = connection,
          store = store,
          retryProvider = retryProvider,
          logger = logger,
        )
      } yield OnboardUserResponse(Proto.encode(userPartyId))
    }
}
