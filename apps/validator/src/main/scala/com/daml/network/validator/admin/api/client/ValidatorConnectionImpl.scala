package com.daml.network.validator.admin.api.client

import com.daml.network.validator.admin.api.client.commands.GrpcValidatorAppClient
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}

final class ValidatorConnectionImpl(
    config: ClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends ValidatorConnection(config, timeouts, loggerFactory) {
  // cached validator reference.
  private val validatorRef: AtomicReference[Option[PartyId]] = new AtomicReference(None)

  override def getValidatorPartyId()(implicit traceContext: TraceContext): Future[PartyId] = {
    val prev = validatorRef.get()
    prev match {
      case Some(partyId) => Future.successful(partyId)
      case None =>
        for {
          partyId <- runCmd(GrpcValidatorAppClient.GetValidatorPartyId())
        } yield {
          // The party id never changes so we don’t need to worry about concurrent setters writing different values.
          validatorRef.set(Some(partyId))
          partyId
        }
    }
  }
}
