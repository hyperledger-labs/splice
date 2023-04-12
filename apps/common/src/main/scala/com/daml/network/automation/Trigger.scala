package com.daml.network.automation

import com.daml.network.util.HasHealth
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.Spanning
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

/** Common base trait for all triggers. */
trait Trigger extends FlagCloseable with NamedLogging with Spanning with HasHealth {

  implicit def ec: ExecutionContext
  implicit def tracer: Tracer

  protected def context: TriggerContext

  protected def timeouts: ProcessingTimeout = context.timeouts

  protected def loggerFactory: NamedLoggerFactory = context.loggerFactory

  /** Run this trigger in the background. MUST be called exactly once. */
  def run(): Unit
}
