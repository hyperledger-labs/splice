package org.lfdecentralizedtrust.splice.test

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.{FutureSupervisor, Threading}
import com.digitalasset.canton.config.{NonNegativeDuration, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.NoReportingTracerProvider
import com.typesafe.scalalogging.Logger
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.slf4j.LoggerFactory

import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.duration.DurationInt

trait HasRetryProvider {

  private lazy val logger: Logger =
    Logger(LoggerFactory.getLogger(getClass.getName))

  val testScheduler: ScheduledExecutorService =
    Threading.singleThreadScheduledExecutor(
      "test-env-sched",
      logger,
    )

  val testRetryProvider = new RetryProvider(
    NamedLoggerFactory.root,
    ProcessingTimeout(),
    new FutureSupervisor.Impl(
      NonNegativeDuration.tryFromDuration(10.seconds),
      NamedLoggerFactory.root,
    )(testScheduler),
    NoOpMetricsFactory,
  )(NoReportingTracerProvider.tracer)

}
