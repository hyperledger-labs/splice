package com.daml.network.validator.admin.grpc

import com.daml.ledger.api.v1.command_service.SubmitAndWaitForTransactionResponse
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.validator.v0._
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.{CoinUtil, UploadablePackage}
import com.daml.network.validator.store.ValidatorAppStore
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.network.CC.CoinRules.CoinRulesRequest
import com.google.protobuf.ByteString
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcValidatorAppService(
    connection: CoinLedgerConnection,
    scanConnection: ScanConnection,
    store: ValidatorAppStore,
    validatorUserName: String,
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
        svc <- scanConnection.getSvcPartyId()
        _ <- CoinUtil.ExplicitDisclosureWorkaround.recordUserHostedAt(
          userPartyId,
          validatorPartyId,
          connection,
        )
      } yield OnboardUserResponse(Some(userPartyId.toPrim.toString))
    }
}
