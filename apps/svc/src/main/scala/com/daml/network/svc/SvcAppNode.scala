package com.daml.network.svc

import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.svc.admin.grpc.GrpcSvcAppService
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.svc.store.SvcAppStore
import com.digitalasset.canton.environment.CantonNode
import com.digitalasset.canton.health.admin.data.{NodeStatus, SimpleStatus, TopologyQueueStatus}
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.{Clock, HasUptime}
import com.digitalasset.canton.topology.UniqueIdentifier
import com.digitalasset.canton.tracing.NoTracing

import scala.concurrent.Future

/** Class representing an SVC app instance.
  *
  * Modelled after Canton's ParticipantNode class.
  */
class SvcAppNode(
    val config: LocalSvcAppConfig,
    val svcAppParameters: SharedCoinAppParameters,
    storage: Storage,
    dummyStore: SvcAppStore,
    service: GrpcSvcAppService,
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
    logger.info("Stopping SVC node")
    Lifecycle.close(storage, dummyStore, service)(logger)
  }
}
