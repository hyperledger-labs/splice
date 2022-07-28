package com.daml.network.validator.admin.grpc

import com.daml.ledger.api.v1.command_service.SubmitAndWaitForTransactionResponse
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.validator.v0._
import com.daml.network.util.CoinUtil
import com.daml.network.validator.store.ValidatorAppStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.network.CC.CoinRules.CoinRulesRequest
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcValidatorAppService(
    connection: CoinLedgerConnection,
    store: ValidatorAppStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    @nowarn("cat=unused")
    ec: ExecutionContext,
    tracer: Tracer,
) extends ValidatorAppServiceGrpc.ValidatorAppService
    with Spanning
    with NamedLogging {

  override def initialize(request: InitializeRequest): Future[InitializeResponse] =
    withSpanFromGrpcContext("GrpcValidatorAppService") { implicit traceContext => span =>
      val validatorName = request.name.fold(sys.error("Field missing : name"))(identity)
      val svcParty =
        request.svc.fold(sys.error("Field missing: svc"))(PartyId.tryFromProtoPrimitive)

      span.setAttribute("name", validatorName)

      def createRulesRequestAndUserHostedAtContracts(
          validatorParty: PartyId
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
        validatorParty <- connection.createPartyAndUser(validatorName)
        _ <- createRulesRequestAndUserHostedAtContracts(validatorParty)
        _ <- store.setValidatorParty(validatorParty)
        _ <- store.setSvcParty(svcParty)
      } yield InitializeResponse(Some(validatorParty.toProtoPrimitive))
    }

  override def onboardUser(request: OnboardUserRequest): Future[OnboardUserResponse] =
    withSpanFromGrpcContext("GrpcValidatorAppService") { implicit traceContext => span =>
      val name = request.name.fold(sys.error("field missing: name"))(identity)

      span.setAttribute("name", name)

      for {
        validatorPartyIdMaybe <- store.getValidatorParty()
        validatorPartyId <- validatorPartyIdMaybe.fold[Future[PartyId]] {
          Future.failed(
            new Error("Validator party not set. Did you forget to call `setupValidator`?")
          )
        }(Future.successful)
        userPartyId <- connection.createPartyAndUser(name)
        svcO <- store.getSvcParty()
        svc = svcO.getOrElse(sys.error("svc party wasn't allocated"))
        _ <- CoinUtil.ExplicitDisclosureWorkaround.recordUserHostedAt(
          userPartyId,
          validatorPartyId,
          connection,
        )
      } yield OnboardUserResponse(Some(userPartyId.toPrim.toString))
    }
}
