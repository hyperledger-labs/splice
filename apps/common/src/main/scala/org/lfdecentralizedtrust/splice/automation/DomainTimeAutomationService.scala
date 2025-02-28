// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import org.lfdecentralizedtrust.splice.config.AutomationConfig
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryProvider}
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeStore,
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContext

// This is a dedicated service because we want to run this once per app whereas apps often have multiple stores and automation services.
final class DomainTimeAutomationService(
    synchronizerAlias: SynchronizerAlias,
    participantAdminConnection: ParticipantAdminConnection,
    config: AutomationConfig,
    clock: Clock,
    retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext, tracer: Tracer)
    extends AutomationService(
      config,
      clock,
      DomainTimeSynchronization.Noop,
      DomainUnpausedSynchronization.Noop,
      retryProvider,
    ) {

  override val companion: DomainTimeAutomationService.type = DomainTimeAutomationService

  private val store =
    new DomainTimeStore(clock, config.maxAllowedDomainTimeDelay, retryProvider, loggerFactory)

  def domainTimeSync: DomainTimeSynchronization = store

  registerTrigger(
    new DomainTimeIngestionTrigger(
      synchronizerAlias,
      store,
      participantAdminConnection,
      triggerContext,
    )
  )

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super.closeAsync() :+ SyncCloseable("Domain Time Store", LifeCycle.close(store)(logger))
}

object DomainTimeAutomationService extends AutomationServiceCompanion {
  override protected[this] def expectedTriggerClasses
      : Seq[AutomationServiceCompanion.TriggerClass] =
    Seq(AutomationServiceCompanion.aTrigger[DomainTimeIngestionTrigger])
}
