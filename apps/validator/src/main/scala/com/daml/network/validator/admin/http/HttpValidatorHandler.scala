package com.daml.network.validator.admin.http

import com.daml.ledger.javaapi.data.User
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.http.v0.{definitions, validator => v0}
import com.daml.network.util.CoinUtil
import com.daml.network.validator.store.ValidatorStore
import com.daml.network.validator.util.ValidatorUtil
import com.digitalasset.canton.lifecycle.FlagCloseable
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class HttpValidatorHandler(
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
) extends v0.ValidatorHandler[String]
    with Spanning
    with NamedLogging {
  private val workflowId = this.getClass.getSimpleName
  private val connection = ledgerClient.connection(workflowId)

  def onboardUser(
      respond: v0.ValidatorResource.OnboardUserResponse.type
  )(
      body: definitions.OnboardUserRequest
  )(damlUser: String): Future[v0.ValidatorResource.OnboardUserResponse] =
    withNewTrace(workflowId) { implicit traceContext => span =>
      val name = body.name

      span.setAttribute("name", name)

      for {
        userPartyId <- connection.getOrAllocateParty(
          name,
          Seq(new User.Right.CanReadAs(store.key.validatorParty.toProtoPrimitive)),
        )
        // TODO(#713) Workaround for the lack of "act-as-any-party" rights
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
          lookupValidatorRightByParty = store.lookupValidatorRightByPartyWithOffset,
          retryProvider = retryProvider,
          flagCloseable = flagCloseable,
          logger = logger,
        )
      } yield definitions.OnboardUserResponse(userPartyId.filterString)
    }

  def getValidatorUserInfo(
      respond: v0.ValidatorResource.GetValidatorUserInfoResponse.type
  )()(
      damlUser: String
  ): Future[v0.ValidatorResource.GetValidatorUserInfoResponse] =
    withNewTrace(workflowId) { _ => _ =>
      Future.successful(
        definitions.GetValidatorUserInfoResponse(
          store.key.validatorParty.filterString,
          validatorUserName,
        )
      )
    }
}
