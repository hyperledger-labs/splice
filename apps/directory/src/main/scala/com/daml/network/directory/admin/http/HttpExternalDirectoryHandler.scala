package com.daml.network.directory.admin.http

import com.daml.ledger.api.v2.participant_offset.ParticipantOffset
import com.daml.network.auth.AuthExtractor.TracedUser
import com.daml.network.codegen.java.cn.directory.DirectoryInstall
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.environment.RetryProvider
import com.daml.network.http.v0.external
import com.daml.network.http.v0.external.directory.DirectoryResource as r0
import com.daml.network.http.v0.{definitions as d0}
import com.daml.network.store.MultiDomainAcsStore
import com.daml.network.util.QualifiedName
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.retry.RetryUtil
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try

class HttpExternalDirectoryHandler(
    domainId: DomainId,
    connection: CNLedgerConnection,
    val globalDomain: DomainAlias,
    retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends external.directory.DirectoryHandler[TracedUser]
    with Spanning
    with NamedLogging {

  protected val workflowId = this.getClass.getSimpleName

  override def createDirectoryEntry(
      respond: r0.CreateDirectoryEntryResponse.type
  )(
      body: d0.CreateDirectoryEntryRequest
  )(tuser: TracedUser): Future[r0.CreateDirectoryEntryResponse] = {
    implicit val TracedUser(user, traceContext) = tuser
    withSpan(s"$workflowId.createDirectoryEntry") { implicit traceContext => _ =>
      for {
        partyId <- connection.getPrimaryParty(user)
        installContractId <- getInstallContract(partyId)
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

  private def getInstallContract(partyId: PartyId)(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[DirectoryInstall.ContractId] = {
    val participantBegin = ParticipantOffset(
      ParticipantOffset.Value.Boundary(
        ParticipantOffset.ParticipantBoundary.PARTICIPANT_BEGIN
      )
    )
    val offset = ParticipantOffset.Value.Absolute(participantBegin.getAbsolute)
    val filter = MultiDomainAcsStore.IngestionFilter(
      partyId,
      Set(QualifiedName(DirectoryInstall.TEMPLATE_ID)),
    )

    retryProvider.retryForClientCalls(
      "fetchInstallContract",
      for {
        contracts <- connection.activeContracts(filter, offset)
        tid = DirectoryInstall.TEMPLATE_ID
        activeContract = contracts._1
          .filter(_.createdEvent.getTemplateId.equals(DirectoryInstall.TEMPLATE_ID))
          .headOption
          .getOrElse(throw new Exception("Unable to find DirectoryInstall contract"))
      } yield new DirectoryInstall.ContractId(activeContract.createdEvent.getContractId()),
      logger,
      DirectoryInstallNotFound(_),
    )
  }
}
