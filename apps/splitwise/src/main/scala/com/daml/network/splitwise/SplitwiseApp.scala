package com.daml.network.splitwise

import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.splitwise.config.LocalSplitwiseAppConfig
import com.daml.network.splitwise.store.SplitwiseAppStore
import com.digitalasset.canton.environment.CantonNode
import com.digitalasset.canton.health.admin.data.{NodeStatus, SimpleStatus, TopologyQueueStatus}
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.{Clock, HasUptime}
import com.digitalasset.canton.topology.UniqueIdentifier
import com.digitalasset.canton.tracing.NoTracing

import scala.concurrent.Future

/** Class representing a Splitwise app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class SplitwiseApp(
    val config: LocalSplitwiseAppConfig,
    val coinAppParameters: SharedCoinAppParameters,
    storage: Storage,
    dummyStore: SplitwiseAppStore,
    scanConnection: ScanConnection,
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
    logger.info("Stopping splitwise node")
    Lifecycle.close(storage, dummyStore, scanConnection)(logger)
  }
}
