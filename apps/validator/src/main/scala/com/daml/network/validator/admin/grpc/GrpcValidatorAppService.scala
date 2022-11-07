package com.daml.network.validator.admin.grpc

import com.daml.ledger.javaapi.data.User
import com.daml.network.environment.{CoinRetries, JavaCoinLedgerClient => CoinLedgerClient}
import com.daml.network.util.{CoinUtil, Proto}
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ValidatorUtil
import com.daml.network.validator.v0.*
import com.digitalasset.canton.lifecycle.FlagCloseable
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
    flagCloseable: FlagCloseable,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ValidatorAppServiceGrpc.ValidatorAppService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcValidatorAppService")

  override def getValidatorUserInfo(request: Empty): Future[GetValidatorUserInfoResponse] =
    withSpanFromGrpcContext("GrpcValidatorAppService") { _ => _ =>
      Future.successful(
        GetValidatorUserInfoResponse(Proto.encode(store.key.validatorParty), validatorUserName)
      )
    }

  override def onboardUser(request: OnboardUserRequest): Future[OnboardUserResponse] =
    withSpanFromGrpcContext("GrpcValidatorAppService") { implicit traceContext => span =>
      val name = request.name

      span.setAttribute("name", name)

      for {
        userPartyId <- connection.getOrAllocateParty(
          name,
          Seq(new User.Right.CanReadAs(store.key.validatorParty.toProtoPrimitive)),
        )
        // TODO(i713) Workaround for the lack of "act-as-any-party" rights
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
          flagCloseable = flagCloseable,
          logger = logger,
        )
        // Create validator right contract so validator can collect validator rewards
        _ <- CoinUtil.createValidatorRight(
          user = userPartyId,
          validator = store.key.validatorParty,
          svc = store.key.svcParty,
          connection = connection,
          lookupValidatorRightByParty = store.lookupValidatorRightByParty,
          retryProvider = retryProvider,
          flagCloseable = flagCloseable,
          logger = logger,
        )
      } yield OnboardUserResponse(Proto.encode(userPartyId))
    }
}
