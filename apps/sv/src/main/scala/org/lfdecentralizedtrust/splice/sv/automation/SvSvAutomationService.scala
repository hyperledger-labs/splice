// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.daml.grpc.adapter.ExecutionSequencerFactory
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  AutomationServiceCompanion,
  SpliceAppAutomationService,
  SqlIndexInitializationTrigger,
}
import org.lfdecentralizedtrust.splice.environment.{
  PackageVersionSupport,
  ParticipantAdminConnection,
  RetryProvider,
  SpliceLedgerClient,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.store.DomainTimeSynchronization
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ExpireValidatorOnboardingTrigger
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotService
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.config.PeriodicBackupDumpConfig
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode

import scala.concurrent.ExecutionContextExecutor

class SvSvAutomationService(
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    config: SvAppBackendConfig,
    svStore: SvSvStore,
    dsoStore: SvDsoStore,
    storage: DbStorage,
    ledgerClient: SpliceLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    synchronizerNodeService: SynchronizerNodeService[LocalSynchronizerNode],
    retryProvider: RetryProvider,
    topologySnapshotConfig: Option[PeriodicBackupDumpConfig],
    packageVersionSupport: PackageVersionSupport,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    esf: ExecutionSequencerFactory,
    actorSystem: ActorSystem,
) extends SpliceAppAutomationService(
      config.automation,
      clock,
      domainTimeSync,
      svStore,
      ledgerClient,
      retryProvider,
      config.parameters,
      packageVersionSupport,
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

  // notice the absence of UpdateHistory: the history for the sv party is not needed as we don't foresee ever adding TxLog for it

  registerTrigger(
    SqlIndexInitializationTrigger(
      storage,
      triggerContext,
    )
  )

  topologySnapshotConfig.foreach(topologySnapshotConfig =>
    registerTrigger(
      new PeriodicTopologySnapshotTrigger(
        config.domains.global.alias,
        topologySnapshotConfig,
        triggerContext,
        synchronizerNodeService,
        participantAdminConnection,
        clock,
      )
    )
  )

  config.identitiesDump.foreach { backupConfig =>
    registerTrigger(
      new BackupNodeIdentitiesTrigger(
        config.domains.global.alias,
        dsoStore,
        backupConfig,
        participantAdminConnection,
        synchronizerNodeService,
        triggerContext,
      )
    )
  }

  def registerOnboardingSnapshotCleanupTrigger(
      onboardingSnapshotService: SvOnboardingSnapshotService
  ): Unit =
    registerTrigger(
      new SvOnboardingSnapshotCleanupTrigger(triggerContext, onboardingSnapshotService)
    )
}

object SvSvAutomationService extends AutomationServiceCompanion {
  override protected[this] def expectedTriggerClasses: Seq[Nothing] = Seq.empty
}
