// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.automation

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.time.SimClock
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.{BaseTest, HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.automation.{TriggerContext, TriggerEnabledSynchronization}
import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.scan.store.historystart.{
  HistoryStart,
  HistoryStartSources,
  HistoryStartStore,
  ScanHistoryStart,
}
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import scala.concurrent.Future

class ScanHistoryStartTriggerTest
    extends AsyncWordSpec
    with BaseTest
    with HasExecutionContext
    with HasActorSystem {

  private val hostedSince =
    CantonTimestamp.tryFromInstant(Instant.parse("2026-01-02T10:15:00Z"))

  private class CountingStore extends HistoryStartStore {
    val value = new AtomicReference[Option[HistoryStart]](None)
    val reads = new AtomicInteger(0)
    override def read(implicit tc: TraceContext) = {
      reads.incrementAndGet()
      Future.successful(value.get())
    }
    override def recordOnce(start: HistoryStart)(implicit tc: TraceContext) =
      Future.successful(value.updateAndGet(_.orElse(Some(start))).getOrElse(start))
    override def reset()(implicit tc: TraceContext) = Future.successful(value.set(None))
  }

  private class JoiningSvSources(hosted: AtomicReference[Option[CantonTimestamp]])
      extends HistoryStartSources {
    override val isFoundingSv = false
    override val historyBackfillEnabled = false
    override def historyBackfilledFromGenesis(implicit tc: TraceContext) =
      Future.successful(Some(false))
    override def dsoPartyHostedSince(implicit tc: TraceContext) =
      Future.successful(hosted.get())
  }

  private lazy val clock = new SimClock(loggerFactory = loggerFactory)
  private lazy val triggerContext: TriggerContext = TriggerContext(
    AutomationConfig(),
    clock,
    clock,
    TriggerEnabledSynchronization.Noop,
    RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory),
    loggerFactory,
    NoOpMetricsFactory,
  )

  "ScanHistoryStartTrigger" should {
    "keep asking until the history start is recorded, then stop" in {
      val store = new CountingStore
      val hosted = new AtomicReference[Option[CantonTimestamp]](None)
      val trigger = new ScanHistoryStartTrigger(
        new ScanHistoryStart(store, new JoiningSvSources(hosted), loggerFactory),
        triggerContext,
      )
      for {
        beforeHosted <- trigger.performWorkIfAvailable()
        _ = store.value.get() shouldBe None
        _ = hosted.set(Some(hostedSince))
        recording <- trigger.performWorkIfAvailable()
        readsWhenRecorded = store.reads.get()
        afterwards <- trigger.performWorkIfAvailable()
      } yield {
        beforeHosted shouldBe false
        recording shouldBe false
        afterwards shouldBe false
        store.value.get() shouldBe Some(HistoryStart.From(hostedSince))
        store.reads.get() shouldBe readsWhenRecorded
      }
    }
  }
}
