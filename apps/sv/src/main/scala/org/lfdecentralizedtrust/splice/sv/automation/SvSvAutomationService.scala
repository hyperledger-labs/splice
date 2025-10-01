// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  AutomationServiceCompanion,
  SpliceAppAutomationService,
  SqlIndexInitializationTrigger,
}
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryProvider,
  SpliceLedgerClient,
}
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ExpireValidatorOnboardingTrigger
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import scala.concurrent.ExecutionContextExecutor

class SvSvAutomationService(
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    config: SvAppBackendConfig,
    svStore: SvSvStore,
    dsoStore: SvDsoStore,
    storage: Storage,
    ledgerClient: SpliceLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    localSynchronizerNode: Option[LocalSynchronizerNode],
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
      ledgerClient,
      retryProvider,
      config.ingestFromParticipantBegin,
      config.ingestUpdateHistoryFromParticipantBegin,
      config.parameters,
    ) {
  override def companion: org.lfdecentralizedtrust.splice.sv.automation.SvSvAutomationService.type =
    SvSvAutomationService
  registerTrigger(
    new ExpireValidatorOnboardingTrigger(
      triggerContext,
      svStore,
      connection(SpliceLedgerConnectionPriority.Low),
    )
  )

  registerTrigger(
    SqlIndexInitializationTrigger(
      storage,
      triggerContext,
    )
  )

  config.identitiesDump.foreach { backupConfig =>
    registerTrigger(
      new BackupNodeIdentitiesTrigger(
        config.domains.global.alias,
        dsoStore,
        backupConfig,
        participantAdminConnection,
        localSynchronizerNode.getOrElse(
          sys.error("Cannot dump identities with no localSynchronizerNode")
        ),
        triggerContext,
      )
    )
  }
}

object SvSvAutomationService extends AutomationServiceCompanion {
  override protected[this] def expectedTriggerClasses: Seq[Nothing] = Seq.empty
}
