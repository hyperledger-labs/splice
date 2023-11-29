package com.daml.network.validator.admin.http

import org.apache.pekko.stream.Materializer
import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.http.v0.{external, definitions as d0}
import com.daml.network.http.v0.external.directory.DirectoryResource as r0
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.DisclosedContracts
import com.daml.network.wallet.UserWalletManager
import com.daml.network.wallet.admin.http.HttpWalletHandlerUtil
import com.digitalasset.canton.logging.NamedLoggerFactory
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class HttpExternalDirectoryHandler(
    override val walletManager: UserWalletManager,
    scanConnection: ScanConnection,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends external.directory.DirectoryHandler[TracedUser]
    with HttpWalletHandlerUtil {

  protected val workflowId = this.getClass.getSimpleName

  override def createDirectoryEntry(
      respond: r0.CreateDirectoryEntryResponse.type
  )(
      body: d0.CreateDirectoryEntryRequest
  )(tuser: TracedUser): Future[r0.CreateDirectoryEntryResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.createDirectoryEntry") { implicit traceContext => _ =>
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
              r0.CreateDirectoryEntryResponse.OK(
                d0.CreateDirectoryEntryResponse(
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

  override def listDirectoryEntries(
      respond: r0.ListDirectoryEntriesResponse.type
  )()(tuser: TracedUser): Future[r0.ListDirectoryEntriesResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.listDirectoryEntries") { implicit traceContext => _ =>
      for {
        entriesWithPayData <- getUserStore(user).flatMap(_.listDirectoryEntries())
        res <- Future.successful {
          r0.ListDirectoryEntriesResponse.OK(
            d0.ListDirectoryEntriesResponse(entries =
              entriesWithPayData
                .map(e =>
                  d0.DirectoryEntryResponse(
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
