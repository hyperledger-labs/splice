package com.daml.network.directory.admin.http

import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.http.v0.{definitions, directory as v0}
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class HttpDirectoryHandler(
    store: DirectoryStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.DirectoryHandler[TraceContext]
    with Spanning
    with NamedLogging {

  private val workflowId = this.getClass.getSimpleName

  override def listEntries(
      respond: v0.DirectoryResource.ListEntriesResponse.type
  )(namePrefix: Option[String], pageSize: Int)(
      extracted: TraceContext
  ): Future[v0.DirectoryResource.ListEntriesResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.listEntries") { _ => _ =>
      // TODO (#7988): pass this as a NonNegativeInt as opposed to re-converting to Int
      for {
        limit <- NonNegativeInt
          .create(pageSize)
          .fold(
            _ =>
              Future.failed(
                Status.INVALID_ARGUMENT
                  .withDescription("pageSize cannot be negative")
                  .asRuntimeException()
              ),
            Future.successful,
          )
        entries <- store.listEntries(namePrefix.getOrElse(""), limit.value)
      } yield definitions.ListEntriesResponse(entries.map(_.toHttp).toVector)
    }
  }

  override def lookupEntryByParty(
      respond: v0.DirectoryResource.LookupEntryByPartyResponse.type
  )(
      party: String
  )(extracted: TraceContext): Future[v0.DirectoryResource.LookupEntryByPartyResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.lookupEntryByParty") { _ => _ =>
      store
        .lookupEntryByParty(PartyId.tryFromProtoPrimitive(party))
        .flatMap {
          case Some(entry) =>
            Future.successful(
              v0.DirectoryResource.LookupEntryByPartyResponse.OK(
                definitions.LookupEntryByPartyResponse(entry.toHttp)
              )
            )
          case None =>
            Future.failed(
              HttpErrorHandler.notFound(s"No directory entry found for party: ${party}")
            )
        }
    }
  }

  override def lookupEntryByName(
      respond: v0.DirectoryResource.LookupEntryByNameResponse.type
  )(
      name: String
  )(extracted: TraceContext): Future[v0.DirectoryResource.LookupEntryByNameResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.lookupEntryByName") { _ => _ =>
      store.lookupEntryByName(name).flatMap {
        case Some(entry) =>
          Future.successful(
            v0.DirectoryResource.LookupEntryByNameResponse.OK(
              definitions.LookupEntryByNameResponse(entry.toHttp)
            )
          )
        case None =>
          Future.failed(HttpErrorHandler.notFound(s"No directory entry found for name: $name"))
      }
    }
  }
  @nowarn("cat=unused")
  override def getProviderPartyId(
      respond: v0.DirectoryResource.GetProviderPartyIdResponse.type
  )()(extracted: TraceContext): Future[v0.DirectoryResource.GetProviderPartyIdResponse] = {
    implicit val tc = extracted
    withSpan(s"$workflowId.getProviderPartyId") { implicit traceContext => _ =>
      Future.successful(
        v0.DirectoryResource.GetProviderPartyIdResponse.OK(
          definitions.GetProviderPartyIdResponse(
            store.providerParty.toProtoPrimitive
          )
        )
      )
    }
  }
}
