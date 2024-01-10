package com.daml.network.validator.admin.http

import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.http.v0.external.cns.CnsResource as r0
import com.daml.network.http.v0.{external, definitions as d0}
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.util.DisclosedContracts
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.admin.http.HttpWalletHandlerUtil
import com.digitalasset.canton.logging.NamedLoggerFactory
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class HttpExternalCnsHandler(
    override val walletManager: UserWalletManager,
    scanConnection: BftScanConnection,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends external.cns.CnsHandler[TracedUser]
    with HttpWalletHandlerUtil {

  protected val workflowId = this.getClass.getSimpleName

  override def createCnsEntry(
      respond: r0.CreateCnsEntryResponse.type
  )(
      body: d0.CreateCnsEntryRequest
  )(tuser: TracedUser): Future[r0.CreateCnsEntryResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.createCnsEntry") { implicit traceContext => _ =>
      val connection = getUserWallet(user).connection
      for {
        partyId <- connection.getPrimaryParty(user)
        cnsRules <- scanConnection.getCnsRules()
        cnsRulesCt = cnsRules.toAssignedContract.getOrElse(
          throw Status.Code.FAILED_PRECONDITION.toStatus
            .withDescription(
              s"CnsRules contract is not assigned to a domain."
            )
            .asRuntimeException()
        )
        update = cnsRulesCt.exercise(
          _.exerciseCnsRules_RequestEntry(
            body.name,
            body.url,
            body.description,
            partyId.toProtoPrimitive,
          )
            .map { e =>
              r0.CreateCnsEntryResponse.OK(
                d0.CreateCnsEntryResponse(
                  e.exerciseResult._1.contractId,
                  e.exerciseResult._2.contractId,
                  body.name,
                  body.url,
                  body.description,
                )
              )
            }
        )
        res <- connection
          .submit(Seq(partyId), Seq(partyId), update)
          .withDisclosedContracts(DisclosedContracts(cnsRules))
          .noDedup
          .yieldResult()
      } yield res
    }
  }

  override def listCnsEntries(
      respond: r0.ListCnsEntriesResponse.type
  )()(tuser: TracedUser): Future[r0.ListCnsEntriesResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.listCnsEntries") { implicit traceContext => _ =>
      for {
        entriesWithPayData <- getUserStore(user).flatMap(_.listCnsEntries())
        res <- Future.successful {
          r0.ListCnsEntriesResponse.OK(
            d0.ListCnsEntriesResponse(entries =
              entriesWithPayData
                .map(e =>
                  d0.CnsEntryResponse(
                    contractId = e.contractId.toString(),
                    name = e.entryName,
                    amount = e.amount.toString(),
                    currency = e.currency.toString(),
                    expiresAt = e.expiresAt.toEpochMilli().toString(),
                    paymentInterval = e.paymentInterval.microseconds.toString(),
                    paymentDuration = e.paymentDuration.microseconds.toString(),
                  )
                )
                .toVector
            )
          )
        }
      } yield res
    }
  }
}
