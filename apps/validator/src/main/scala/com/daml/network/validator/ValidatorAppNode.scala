package com.daml.network.validator

import com.daml.network.validator.config.{LocalValidatorAppConfig, ValidatorAppParameters}
import com.daml.network.validator.store.ValidatorAppStore
import com.digitalasset.canton.environment.CantonNode
import com.digitalasset.canton.health.admin.data.{NodeStatus, SimpleStatus, TopologyQueueStatus}
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.{Clock, HasUptime}
import com.digitalasset.canton.topology.UniqueIdentifier
import com.digitalasset.canton.tracing.NoTracing

import scala.concurrent.Future

/** Class representing a Validator app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class ValidatorAppNode(
    val config: LocalValidatorAppConfig,
    val validatorNodeParameters: ValidatorAppParameters,
    storage: Storage,
    dummyStore: ValidatorAppStore,
    override protected val clock: Clock,
    val loggerFactory: NamedLoggerFactory,
) extends CantonNode // TODO(Arne): CantonNode needs to be forked or generalized.
    with NamedLogging
    with HasUptime
    with NoTracing {

  // TODO(Arne): fork or generalize status definition.
  override def status: Future[NodeStatus.Status] = {
    val status = SimpleStatus(
      UniqueIdentifier.tryFromProtoPrimitive("example::default"),
      uptime(),
      Map("admin" -> config.adminApi.port),
      storage.isActive,
      TopologyQueueStatus(0, 0, 0),
    )
    Future.successful(status)
  }

  override def close(): Unit = {
    logger.info("Stopping validator node")
    Lifecycle.close(storage, dummyStore)(logger)
  }
}
