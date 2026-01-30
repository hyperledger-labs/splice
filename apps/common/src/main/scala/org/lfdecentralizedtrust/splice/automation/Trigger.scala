// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import org.lfdecentralizedtrust.splice.util.HasHealth
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.Mutex
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Common base trait for all triggers. */
trait Trigger extends FlagCloseable with NamedLogging with Spanning with HasHealth {

  implicit def ec: ExecutionContext
  implicit def tracer: Tracer
  protected val mutex = Mutex()

  protected def context: TriggerContext

  protected def metrics: TriggerMetrics = new TriggerMetrics(context.metricsFactory)

  protected def extraMetricLabels: Seq[(String, String)] = Seq.empty

  protected def timeouts: ProcessingTimeout = context.timeouts

  protected def loggerFactory: NamedLoggerFactory = context.loggerFactory

  /** Run this trigger in the background. MUST be called exactly once. */
  def run(paused: Boolean): Unit

  /** Pauses the trigger.
    *
    * The resulting Future completes when the trigger is done executing
    * any work started before the pause. For most triggers, it means commands
    * submitted to the ledger have completed, i.e., after the Future completes,
    * the participant the trigger is connected to won't see any future changes
    * initiated by the trigger.
    * It does NOT mean all previous changes initiated by the trigger have finished
    * propagating to the entire distributed network.
    */
  def pause(): Future[Unit]

  /** Resumes the trigger */
  def resume(): Unit

  /** Waits until the trigger is ready to execute the next unit of work */
  protected def waitForReadyToWork()(implicit tc: TraceContext): Future[Unit] =
    context.triggerEnabledSync.waitForTriggerEnabled()
}
