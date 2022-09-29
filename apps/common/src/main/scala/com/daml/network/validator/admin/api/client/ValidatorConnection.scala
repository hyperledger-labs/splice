package com.daml.network.validator.admin.api.client

import com.daml.network.admin.api.client.AppConnection
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContextExecutor, Future}

// We abstract over the validator connection to break the dependency
// cycle where the validator app depends on the wallet daml model and
// the wallet app depends on the validator connection.
// TODO(#912) fix that by splitting the model into a separate target.
abstract class ValidatorConnection(
    config: ClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends AppConnection(config, timeouts, loggerFactory) {

  override val serviceName = "validator"

  /** Query for the Validator party id. This caches the result internally so
    * clients can call this repeatedly without having to implement caching themselves.
    */
  def getValidatorPartyId()(implicit traceContext: TraceContext): Future[PartyId]
}
