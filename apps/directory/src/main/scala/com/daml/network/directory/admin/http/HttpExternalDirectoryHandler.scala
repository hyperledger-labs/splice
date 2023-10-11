package com.daml.network.directory.admin.http

import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.codegen.java.cn.directory.DirectoryInstall
import com.daml.network.environment.RetryProvider
import com.daml.network.http.v0.external
import com.daml.network.http.v0.external.directory.DirectoryResource as r0
import com.daml.network.http.v0.{definitions as d0}
import com.daml.network.wallet.admin.http.HttpWalletHandlerUtil
import com.daml.network.wallet.UserWalletManager
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.retry.RetryUtil
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

class HttpExternalDirectoryHandler(
    override val walletManager: UserWalletManager,
    domainId: DomainId,
    val globalDomain: DomainAlias,
    retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
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
        installContractId <- getInstallContract(user)
        update <- Future.successful {
          installContractId
            .exerciseDirectoryInstall_RequestEntry(body.name, body.url, body.description)
            .map { _ =>
              r0.CreateDirectoryEntryResponse.OK(
                d0.CreateDirectoryEntryResponse(
                  body.name,
                  body.url,
                  body.description,
                )
              )
            }
        }
        res <- connection
          .submit(Seq(partyId), Seq(partyId), update)
          .withDomainId(domainId)
          .noDedup
          .yieldResult()
      } yield res
    }
  }

  case class DirectoryInstallNotFound(operationName: String) extends RetryUtil.ExceptionRetryable {
    override def retryOK(outcome: Try[_], logger: TracedLogger)(implicit
        tc: TraceContext
    ): RetryUtil.ErrorKind = RetryUtil.TransientErrorKind
  }

  private def getInstallContract(user: String)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DirectoryInstall.ContractId] = {
    retryProvider.retryForClientCalls(
      "fetchInstallContract",
      getUserStore(user).flatMap(_.getDirectoryInstall().map(_.contractId)),
      logger,
      DirectoryInstallNotFound(_),
    )
  }
}
