// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.MetricQualification.Traffic
import org.lfdecentralizedtrust.splice.environment.{
  SpliceMetrics,
  RetryProvider,
  TopologyAdminConnection,
}
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.transaction.DomainParametersState
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import scala.concurrent.{blocking, ExecutionContext, Future, Promise}

abstract class DomainUnpausedSynchronization {

  /** Blocks until the domain has a non-zero confirmation request rate
    */
  def waitForDomainUnpaused()(implicit tc: TraceContext): Future[Unit]
}

object DomainUnpausedSynchronization {
  final class NoopDomainUnpausedSynchronization extends DomainUnpausedSynchronization {
    override def waitForDomainUnpaused()(implicit tc: TraceContext): Future[Unit] = Future.unit
  }

  val Noop = new NoopDomainUnpausedSynchronization()
}

final class DomainParamsStore(
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends DomainUnpausedSynchronization
    with AutoCloseable
    with NamedLogging {

  private val metrics: DomainParamsStore.Metrics =
    new DomainParamsStore.Metrics(retryProvider.metricsFactory)

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private var state: DomainParamsStore.State = DomainParamsStore.State(
    None,
    None,
  )

  override def waitForDomainUnpaused()(implicit tc: TraceContext): Future[Unit] = {
    val promiseO = checkDomainUnpaused()
    promiseO match {
      case None => Future.unit
      case Some(promise) =>
        retryProvider
          .waitUnlessShutdown(promise.future)
          .failOnShutdownTo {
            Status.UNAVAILABLE
              .withDescription(
                s"Aborted waitForDomainUnpaused, as RetryProvider(${retryProvider.loggerFactory.properties}) is shutting down"
              )
              .asRuntimeException()
          }
    }
  }

  private def checkDomainUnpaused()(implicit tc: TraceContext): Option[Promise[Unit]] = blocking {
    synchronized {
      state.lastParams match {
        // If we are just starting up, try to submit something to not delay startup.
        // We'll block on the next update if the domain is actually paused.
        case None =>
          None
        case Some(lastParams) =>
          val unpaused = isDomainUnpaused(lastParams)
          if (unpaused) {
            None
          } else {
            logger.info(
              show"Domain is currently paused. This is expected during hard domain migrations."
            )
            state.domainUnpausedPromise match {
              case Some(promise) => Some(promise)
              case None =>
                val promise = Promise[Unit]()
                state = state.copy(domainUnpausedPromise = Some(promise))
                Some(promise)
            }
          }
      }
    }
  }

  def ingestDomainParams(
      params: TopologyAdminConnection.TopologyResult[
        DomainParametersState
      ]
  )(implicit tc: TraceContext): Future[Unit] = Future {
    val unpaused = isDomainUnpaused(params)
    metrics.confirmationRequestsMaxRate.updateValue(
      params.mapping.parameters.confirmationRequestsMaxRate.value
    )
    blocking {
      synchronized {
        val newState = state.copy(lastParams = Some(params))
        state.domainUnpausedPromise match {
          case None =>
            state = newState
          case Some(promise) =>
            if (unpaused) {
              logger.info(show"Domain is now unpaused")
              promise.success(())
              state = newState.copy(domainUnpausedPromise = None)
            } else {
              logger.info(show"Domain is still paused")
              state = newState
            }
        }
      }
    }
  }

  private def isDomainUnpaused(
      params: TopologyAdminConnection.TopologyResult[DomainParametersState]
  ) = params.mapping.parameters.confirmationRequestsMaxRate > NonNegativeInt.zero

  override def close(): Unit = {
    metrics.close()
  }
}

object DomainParamsStore {

  private final case class State(
      lastParams: Option[TopologyAdminConnection.TopologyResult[DomainParametersState]],
      domainUnpausedPromise: Option[Promise[Unit]],
  )

  private class Metrics(metricsFactory: LabeledMetricsFactory) extends AutoCloseable {

    private val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "domain_params_store"

    val confirmationRequestsMaxRate: Gauge[Int] =
      metricsFactory.gauge(
        MetricInfo(
          name = prefix :+ "confirmation-requests-max-rate",
          summary = "DynamicDomainParameters.confirmationRequestsMaxRate",
          description =
            "Last known value of DynamicDomainParameters.confirmationRequestsMaxRate on the configured global domain.",
          qualification = Traffic,
        ),
        -1,
      )(MetricsContext.Empty)

    override def close(): Unit = {
      confirmationRequestsMaxRate.close()
    }
  }
}
