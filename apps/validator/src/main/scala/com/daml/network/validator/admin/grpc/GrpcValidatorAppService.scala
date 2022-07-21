package com.daml.network.validator.admin.grpc

import com.daml.error.definitions.LedgerApiErrors.ConsistencyErrors.ContractNotFound
import com.digitalasset.canton.protocol.messages.LocalReject.ConsistencyRejections.{
  InactiveContracts,
  LockedContracts,
}
import com.daml.network.examples.v0.{
  ValidatorAppServiceGrpc,
  OnboardUserRequest,
  OnboardUserResponse,
  SetupValidatorRequest,
  SetupValidatorResponse,
  SomeDummyRequest,
  SomeDummyResponse,
}
import com.digitalasset.canton.ledger.api.client.{CommandSubmitterWithRetry, DecodeUtil}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.validator.store.ValidatorAppStore
import com.daml.ledger.api.v1.commands.{Command => ScalaCommand}
import com.daml.ledger.api.v1.completion.{Completion}
import com.daml.ledger.api.refinements.{ApiTypes => A}
import com.daml.ledger.client.binding.{Contract, Primitive => P}
import com.digitalasset.canton.error.ErrorCodeUtils
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.CC.CoinRules.{CoinRules, CoinRulesRequest}
import com.digitalasset.network.CC.Scripts.Util.{CCUserHostedAt}
import io.opentelemetry.api.trace.Tracer
import com.digitalasset.canton.lifecycle.Lifecycle

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future, TimeoutException}
import scala.util.{Failure, Success}
import scala.util.control.NonFatal
import com.daml.network.util.CoinUtil

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
  override def dummyFunction(request: SomeDummyRequest): Future[SomeDummyResponse] =
    withSpanFromGrpcContext("GrpcDummyService") { implicit traceContext => span =>
      // spans can be used to, e.g., measure how long a certain function takes on average and visualize code flows.
      // We can likely mostly ignore spans at first.
      request.someString.foreach(span.setAttribute("dummyString", _))
      // trace context automatically included here
      logger.info(s"Received dummy request $request")
      Future.successful(SomeDummyResponse(request.someNumber + 1))
    }

  override def setupValidator(request: SetupValidatorRequest): Future[SetupValidatorResponse] =
    withSpanFromGrpcContext("GrpcValidatorAppService") { implicit traceContext => span =>
      val validatorName = request.name.fold(sys.error("Field missing : name"))(identity)
      val svcParty = request.svc.fold(sys.error("Field missing: svc"))(A.Party(_))

      span.setAttribute("name", validatorName)

      val createValidatorPartyAndUser = connection.bootstrapUser(validatorName)

      def createContracts(validatorParty: PartyId): Future[Unit] = {
        val coinRulesReq = CoinRulesRequest(user = validatorParty.toPrim, svc = svcParty)
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
        validatorParty <- createValidatorPartyAndUser
        _ <- createContracts(validatorParty)
        _ <- store.setValidatorParty(validatorParty)
      } yield SetupValidatorResponse(Some(validatorParty.toPrim.toString))
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
        } {
          Future.successful _
        }
        userPartyId <- connection.bootstrapUser(name)
        _ <- CoinUtil.ExplicitDisclosureWorkaround.recordUserHostedAt(
          userPartyId,
          validatorPartyId,
          connection,
        )
      } yield OnboardUserResponse(Some(userPartyId.toPrim.toString))
    }
}
