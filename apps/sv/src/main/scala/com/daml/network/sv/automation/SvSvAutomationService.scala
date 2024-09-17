// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation

import org.apache.pekko.stream.Materializer
import com.daml.network.automation.{
  AssignTrigger,
  AutomationServiceCompanion,
  SpliceAppAutomationService,
}
import com.daml.network.environment.{SpliceLedgerClient, PackageIdResolver, RetryProvider}
import com.daml.network.store.{DomainTimeSynchronization, DomainUnpausedSynchronization}
import com.daml.network.sv.automation.singlesv.ExpireValidatorOnboardingTrigger
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.store.{SvDsoStore, SvSvStore}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

class SvSvAutomationService(
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    config: SvAppBackendConfig,
    svStore: SvSvStore,
    dsoStore: SvDsoStore,
    ledgerClient: SpliceLedgerClient,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends SpliceAppAutomationService(
      config.automation,
      clock,
      domainTimeSync,
      domainUnpausedSync,
      svStore,
      PackageIdResolver
        .inferFromAmuletRules(
          clock,
          dsoStore,
          loggerFactory,
        ),
      ledgerClient,
      retryProvider,
      config.ingestFromParticipantBegin,
      config.ingestUpdateHistoryFromParticipantBegin,
    ) {
  override def companion = SvSvAutomationService
  registerTrigger(new ExpireValidatorOnboardingTrigger(triggerContext, svStore, connection))
  registerTrigger(new AssignTrigger(triggerContext, svStore, connection, store.key.svParty))
}

object SvSvAutomationService extends AutomationServiceCompanion {
  override protected[this] def expectedTriggerClasses = Seq.empty
}
