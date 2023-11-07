package com.daml.network.directory.admin.http

import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.codegen.java.cn.directory.{DirectoryInstall, DirectoryInstallRequest}
import com.daml.network.environment.RetryProvider
import com.daml.network.http.v0.external
import com.daml.network.http.v0.external.directory.DirectoryResource as r0
import com.daml.network.http.v0.{definitions as d0}
import com.daml.network.util.AssignedContract
import com.daml.network.wallet.admin.http.HttpWalletHandlerUtil
import com.daml.network.wallet.UserWalletManager
import com.digitalasset.canton.logging.{NamedLoggerFactory, TracedLogger}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.retry.RetryUtil
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

class HttpExternalDirectoryHandler(
    override val walletManager: UserWalletManager,
    directoryProvider: PartyId,
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
        install <- getInstallContract(user)
        update = install.exercise(
          _.exerciseDirectoryInstall_RequestEntry(body.name, body.url, body.description)
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

  def createDirectoryInstall(respond: r0.CreateDirectoryInstallResponse.type)()(
      tuser: TracedUser
  ): Future[r0.CreateDirectoryInstallResponse] = {
    implicit val TracedUser(user, traceContext) = tuser

    withSpan(s"$workflowId.createDirectoryInstall") { implicit traceContext => _ =>
      val userWallet = getUserWallet(user)
      val connection = userWallet.connection
      for {
        partyId <- connection.getPrimaryParty(user)
        domainId <- userWallet.store.getInstall() map (_.domain)
        create <- connection
          .submit(
            Seq(partyId),
            Seq(partyId),
            new DirectoryInstallRequest(
              directoryProvider.toProtoPrimitive,
              partyId.toProtoPrimitive,
            ).create,
          )
          .withDomainId(domainId)
          .noDedup
          .yieldResult()
        res <- Future.successful {
          r0.CreateDirectoryInstallResponse.OK(
            d0.CreateDirectoryInstallResponse(
              create.contractId.contractId
            )
          )
        }
      } yield res
    }
  }

  def fetchDirectoryInstall(
      respond: r0.FetchDirectoryInstallResponse.type
  )()(tuser: TracedUser): Future[r0.FetchDirectoryInstallResponse] = {
    implicit val TracedUser(user, traceContext) = tuser

    withSpan(s"$workflowId.fetchDirectoryInstall") { implicit traceContext => _ =>
      for {
        installContract <- getUserStore(user).flatMap(_.getDirectoryInstall())
        res <- Future.successful {
          r0.FetchDirectoryInstallResponse.OK(
            d0.FetchDirectoryInstallResponse(
              contractId = installContract.contractId.toString(),
              entryFee = installContract.payload.entryFee.toString(),
              entryLifetime = installContract.payload.entryLifetime.microseconds.toString(),
            )
          )
        }
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
  ): Future[AssignedContract[DirectoryInstall.ContractId, ?]] =
    retryProvider.retryForClientCalls(
      "fetchInstallContract",
      getUserStore(user).flatMap(_.getDirectoryInstall()),
      logger,
      DirectoryInstallNotFound(_),
    )
}
