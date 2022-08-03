package com.daml.network.directory.provider.admin.api.client

import com.daml.network.admin.api.client.AppConnection
import com.daml.network.directory.provider.admin.api.client.commands.DirectoryProviderCommands
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContextExecutor, Future}
import java.util.concurrent.atomic.AtomicReference

/** Connection to the admin API of the DirectoryProvider.
  */
final class DirectoryProviderConnection(
    config: ClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends AppConnection(config, timeouts, loggerFactory) {
  override val serviceName = "directory provider"
  // cached provider reference.
  private val providerRef: AtomicReference[Option[PartyId]] = new AtomicReference(None)

  /** Query for the provider party id. This caches the result internally so
    * clients can call this repeatedly without having to implement caching themselves.
    */
  def getProviderPartyId()(implicit traceContext: TraceContext): Future[PartyId] = {
    val prev = providerRef.get()
    prev match {
      case Some(partyId) => Future.successful(partyId)
      case None =>
        for {
          partyId <- runCmd(DirectoryProviderCommands.GetProviderPartyId())
        } yield {
          // The party id never changes so we don’t need to worry about concurrent setters writing different values.
          providerRef.set(Some(partyId))
          partyId
        }
    }
  }
}
