package com.daml.network.directory.admin.http

import com.daml.network.admin.http.HttpErrorHandler
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.http.v0.{definitions, directory as v0}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class HttpDirectoryHandler(
    store: DirectoryStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends v0.DirectoryHandler
    with Spanning
    with NamedLogging {

  override def listEntries(
      respond: v0.DirectoryResource.ListEntriesResponse.type
  )(namePrefix: Option[String], pageSize: Int): Future[v0.DirectoryResource.ListEntriesResponse] =
    withNewTrace("HttpDirectoryHandler") { implicit traceContext => _ =>
      for { entries <- store.listEntries(namePrefix.getOrElse(""), pageSize) } yield definitions
        .ListEntriesResponse(entries.map(_.toJson).toVector)
    }

  override def lookupEntryByParty(
      respond: v0.DirectoryResource.LookupEntryByPartyResponse.type
  )(party: String): Future[v0.DirectoryResource.LookupEntryByPartyResponse] =
    withNewTrace("HttpDirectoryHandler") { implicit traceContext => _ =>
      store
        .lookupEntryByParty(PartyId.tryFromProtoPrimitive(party))
        .flatMap {
          case Some(entry) =>
            Future.successful(
              v0.DirectoryResource.LookupEntryByPartyResponse.OK(
                definitions.LookupEntryByPartyResponse(entry.toJson)
              )
            )
          case None =>
            Future.failed(
              HttpErrorHandler.notFound(s"No directory entry found for party: ${party}")
            )
        }
    }

  override def lookupEntryByName(
      respond: v0.DirectoryResource.LookupEntryByNameResponse.type
  )(name: String): Future[v0.DirectoryResource.LookupEntryByNameResponse] =
    withNewTrace("HttpDirectoryHandler") { implicit traceContext => _ =>
      store.lookupEntryByName(name).flatMap {
        case Some(entry) =>
          Future.successful(
            v0.DirectoryResource.LookupEntryByNameResponse.OK(
              definitions.LookupEntryByNameResponse(entry.toJson)
            )
          )
        case None =>
          Future.failed(HttpErrorHandler.notFound(s"No directory entry found for name: $name"))
      }
    }

  @nowarn("cat=unused")
  override def getProviderPartyId(
      respond: v0.DirectoryResource.GetProviderPartyIdResponse.type
  )(): Future[v0.DirectoryResource.GetProviderPartyIdResponse] =
    withNewTrace("HttpDirectoryHandler") { implicit traceContext => _ =>
      Future.successful(
        v0.DirectoryResource.GetProviderPartyIdResponse.OK(
          definitions.GetProviderPartyIdResponse(
            store.providerParty.toProtoPrimitive
          )
        )
      )
    }
}
