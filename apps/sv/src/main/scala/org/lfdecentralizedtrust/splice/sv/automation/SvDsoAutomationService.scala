// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import cats.implicits.catsSyntaxOptionId
import com.daml.grpc.adapter.ExecutionSequencerFactory
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.lifecycle.{
  AsyncCloseable,
  AsyncOrSyncCloseable,
  LifeCycle,
  SyncCloseable,
}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.{Clock, WallClock}
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.Mutex
import io.opentelemetry.api.trace.Tracer
import monocle.Monocle.toAppliedFocusOps
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.automation.{
  AutomationServiceCompanion,
  SpliceAppAutomationService,
}
import org.lfdecentralizedtrust.splice.automation.AutomationServiceCompanion.{
  TriggerClass,
  aTrigger,
}
import org.lfdecentralizedtrust.splice.config.{
  EnabledFeaturesConfig,
  NetworkAppClientConfig,
  SpliceInstanceNamesConfig,
  UpgradesConfig,
}
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.scan.admin.api.client.{
  BftScanConnection,
  ScanConnection,
  SingleScanConnection,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import org.lfdecentralizedtrust.splice.store.{DomainTimeSynchronization, UnavailablePartiesStore}
import org.lfdecentralizedtrust.splice.sv.{CantonBftSequencerConfig, LocalSynchronizerNode}
import org.lfdecentralizedtrust.splice.sv.automation.SvDsoAutomationService.{
  LocalSequencerClientConfig,
  LocalSequencerClientContext,
}
import org.lfdecentralizedtrust.splice.sv.automation.confirmation.*
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.SvTaskBasedTrigger
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.*
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.offboarding.{
  SvOffboardingMediatorTrigger,
  SvOffboardingPartyToParticipantProposalTrigger,
  SvOffboardingSequencerTrigger,
}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.onboarding.*
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.scan.AggregatingScanConnection
import org.lfdecentralizedtrust.splice.sv.config.{SequencerPruningConfig, SvAppBackendConfig}
import org.lfdecentralizedtrust.splice.sv.lsu.{
  LsuAnnouncementTrigger,
  LsuSequencingTestTrigger,
  LsuTransferTrafficTrigger,
  LsuTrigger,
}
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeReconciler
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder

import scala.concurrent.{ExecutionContextExecutor, Future}

class SvDsoAutomationService(
    clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    config: SvAppBackendConfig,
    svStore: SvSvStore,
    dsoStore: SvDsoStore,
    ledgerClient: SpliceLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    synchronizerNodeService: SynchronizerNodeService[LocalSynchronizerNode],
    upgradesConfig: UpgradesConfig,
    spliceInstanceNamesConfig: SpliceInstanceNamesConfig,
    override protected val loggerFactory: NamedLoggerFactory,
    grpcClientMetrics: GrpcClientMetrics,
    packageVersionSupport: PackageVersionSupport,
    synchronizerId: SynchronizerId,
    enabledFeatures: EnabledFeaturesConfig,
    val synchronizerNodeReconciler: SynchronizerNodeReconciler,
    unavailablePartiesStore: UnavailablePartiesStore,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
    httpClient: HttpClient,
    templateJsonDecoder: TemplateJsonDecoder,
    esf: ExecutionSequencerFactory,
    tc: TraceContext,
) extends SpliceAppAutomationService(
      config.automation,
      clock,
      domainTimeSync,
      dsoStore,
      ledgerClient,
      retryProvider,
      config.parameters,
      packageVersionSupport,
    ) {

  override def companion
      : org.lfdecentralizedtrust.splice.sv.automation.SvDsoAutomationService.type =
    SvDsoAutomationService

  private val mutex = Mutex()

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private var ownScanConnectionF: Option[Future[SingleScanConnection]] = None

  /** Returns a [[SingleScanConnection]] to the scan app of our own SV node.
    *
    * Note: a [[SingleScanConnection]] does not hold any significant resources, however,
    * building it involves running a version compatibility check on the scan API,
    * which can fail if scan is not ready and adds unnecessary overhead.
    * We therefore create it lazily and cache the result once it succeeds.
    *
    * Do not store the result of this method in a long-lived variable,
    * instead call it every time you need a connection to the local scan app.
    */
  private def getOrCreateOwnScanConnection()(implicit
      tc: TraceContext
  ): Future[SingleScanConnection] =
    scala.concurrent.blocking {
      mutex.exclusive {
        ownScanConnectionF match {
          case Some(future) if future.isCompleted && future.value.exists(_.isFailure) =>
            logger.info(
              s"Previous attempt to establish scan connection to own scan app failed, retrying..."
            )
            ownScanConnectionF = None
          case _ => // do nothing
        }
        ownScanConnectionF match {
          case Some(future) =>
            future
          case None =>
            val future = ScanConnection
              .singleUncached(
                ScanAppClientConfig(NetworkAppClientConfig(config.scan.internalUrl)),
                upgradesConfig,
                clock,
                retryProvider,
                loggerFactory,
                retryConnectionOnInitialFailure = false,
              )
            ownScanConnectionF = Some(future)
            future
        }
      }
    }

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile
  private var peerScanConnectionF: Option[Future[BftScanConnection]] = None

  /** Returns a [[BftScanConnection]] to the all peer scan apps, i.e.,
    * to all scan apps except the scan app of our own SV node.
    *
    * Note: similar to [[getOrCreateOwnScanConnection]],
    * we create the [[BftScanConnection]] lazily and cache the result once it succeeds,
    * as building it involves running initialization code that can fail during startup.
    *
    * Do not store the result of this method in a long-lived variable,
    * instead call it every time you need a BFT connection to peer scan apps.
    */
  private def getOrCreatePeerScanConnection()(implicit
      tc: TraceContext
  ): Future[BftScanConnection] =
    scala.concurrent.blocking {
      mutex.exclusive {
        peerScanConnectionF match {
          case Some(future) if future.isCompleted && future.value.exists(_.isFailure) =>
            logger.info(
              s"Previous attempt to establish bft scan connection to peer scan apps failed, retrying..."
            )
            peerScanConnectionF = None
          case _ => // do nothing
        }
        peerScanConnectionF match {
          case Some(future) =>
            future
          case None =>
            val future = BftScanConnection
              .peerScanConnection(
                () =>
                  BftScanConnection.Bft.getPeerScansFromDsoRules(
                    dsoStore,
                    dsoStore.key.svParty,
                  )(tc, ec),
                ledgerClient,
                ScanAppClientConfig.DefaultScansRefreshInterval,
                ScanAppClientConfig.DefaultAmuletRulesCacheTimeToLive,
                upgradesConfig,
                clock,
                retryProvider,
                loggerFactory,
              )(ec, tc, mat, httpClient, templateJsonDecoder)
            peerScanConnectionF = Some(future)
            future
        }
      }
    }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    SyncCloseable(
      "dso-delegate-based-automation",
      LifeCycle.close(dsoDelegateBasedAutomation)(logger),
    ) +: (super.closeAsync() ++
      // super.closeAsync() waits for all triggers to close, so we do not need to worry
      // about synchronization when closing the scan connections here.
      ownScanConnectionF
        .map(connectionF =>
          AsyncCloseable(
            "scan-connection",
            connectionF.transform {
              case scala.util.Success(c) => scala.util.Try(c.close())
              case scala.util.Failure(_) => scala.util.Success(())
            },
            timeouts.shutdownNetwork,
          )
        )
        .toList ++ peerScanConnectionF
        .map(connectionF =>
          AsyncCloseable(
            "bft-scan-connection",
            connectionF.transform {
              case scala.util.Success(c) => scala.util.Try(c.close())
              case scala.util.Failure(_) => scala.util.Success(())
            },
            timeouts.shutdownNetwork,
          )
        )
        .toList)

  private val packageVettingService = new PackageVettingLookupService(
    config.packageVettingCache,
    connection(
      SpliceLedgerConnectionPriority.Medium
    ), // priority doesn't matter, we don't do command submissions
    synchronizerId,
    clock,
    loggerFactory,
    retryProvider,
    triggerContext.metricsFactory,
  )

  // notice the absence of UpdateHistory: the history for the dso party is duplicate with Scan

  private[splice] val dsoDelegateBasedAutomation =
    new DsoDelegateBasedAutomationService(
      clock,
      domainTimeSync,
      config,
      SvTaskBasedTrigger.Context(
        dsoStore,
        connection,
        config.delegatelessAutomationExpectedTaskDuration,
        config.delegatelessAutomationExpiredRewardCouponBatchSize,
        config.delegatelessAutomationExpiredRewardCouponNumBatches,
        packageVersionSupport,
        packageVettingService,
      ),
      () => getOrCreateOwnScanConnection(),
      () => getOrCreatePeerScanConnection(),
      retryProvider,
      loggerFactory,
      unavailablePartiesStore,
    )

  // required for triggers that must run in sim time as well
  private val wallClockTriggerContext = triggerContext
    .focus(_.clock)
    .replace(
      new WallClock(triggerContext.timeouts, triggerContext.loggerFactory)
    )

  private val onboardingTriggerContext = wallClockTriggerContext
    .focus(_.config.pollingInterval)
    .replace(
      config.onboardingPollingInterval.getOrElse(wallClockTriggerContext.config.pollingInterval)
    )

  // Triggers that require namespace permissions and the existence of the DsoRules and AmuletRules contracts
  def registerPostOnboardingTriggers(): Unit = {
    registerTrigger(
      new SvOnboardingRequestTrigger(
        triggerContext,
        dsoStore,
        svStore,
        config,
        connection(SpliceLedgerConnectionPriority.High),
      )
    )
    // Register optional BFT triggers
    if (triggerContext.config.enableCometbftReconciliation) {
      registerTrigger(
        new PublishLocalCometBftNodeConfigTrigger(
          triggerContext,
          dsoStore,
          connection(SpliceLedgerConnectionPriority.High),
          synchronizerNodeService,
        )
      )
      registerTrigger(
        new ReconcileCometBftNetworkConfigWithDsoRulesTrigger(
          triggerContext,
          dsoStore,
          synchronizerNodeService,
        )
      )
    }
    registerTrigger(
      new SvOffboardingPartyToParticipantProposalTrigger(
        triggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOffboardingMediatorTrigger(
        wallClockTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOffboardingSequencerTrigger(
        wallClockTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvClearOnboardingFlagTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
        config.enableOnboardingParticipantPromotionDelay,
      )
    )
    registerTrigger(
      new SvOnboardingPartyToParticipantProposalTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOnboardingSequencerTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new SvOnboardingMediatorProposalTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )

    registerTrigger(
      new SvNamespaceMembershipTrigger(
        onboardingTriggerContext,
        dsoStore,
        participantAdminConnection,
      )
    )

    registerTrigger(
      new ReconcileDynamicSynchronizerParametersTrigger(
        triggerContext,
        dsoStore,
        participantAdminConnection,
        config,
      )
    )

    registerTrigger(
      new ReconcileSequencingParametersTrigger(
        triggerContext,
        participantAdminConnection,
        config.cantonBftSequencingParameters,
        config.domains.global.alias,
      )
    )

    registerTrigger(
      new LsuAnnouncementTrigger(
        triggerContext,
        dsoStore,
        participantAdminConnection,
        config.domains.global.alias,
      )
    )

    lazy val aggregatingScanConnection = new AggregatingScanConnection(
      dsoStore,
      upgradesConfig,
      triggerContext.clock,
      triggerContext.retryProvider,
      triggerContext.loggerFactory,
    )
    def registerTriggersForSynchronizers(node: LocalSynchronizerNode): Unit = {
      node.sequencerConfig match {
        case CantonBftSequencerConfig() =>
          registerTrigger(
            new SvBftSequencerPeerOffboardingTrigger(
              triggerContext,
              dsoStore,
              node.sequencerAdminConnection,
              aggregatingScanConnection,
            )
          )
          registerTrigger(
            new SvBftSequencerPeerOnboardingTrigger(
              triggerContext,
              dsoStore,
              node.sequencerAdminConnection,
              aggregatingScanConnection,
            )
          )
        case _ =>
      }
    }

    registerTriggersForSynchronizers(synchronizerNodeService.nodes.current)
    synchronizerNodeService.nodes.successor.foreach(registerTriggersForSynchronizers)
  }

  def registerLsuTriggers(): Unit = {
    synchronizerNodeService.nodes.successor match {
      case Some(successorSynchronizerNode) =>
        registerTrigger(
          new LsuTrigger(
            triggerContext,
            synchronizerNodeReconciler,
            synchronizerNodeService.nodes,
            successorSynchronizerNode,
            participantAdminConnection,
            store,
            config.domainMigrationDumpPath.getOrElse(
              throw new IllegalArgumentException("Domain migration dump path must be set for LSU")
            ),
            config.bftSequencerConnection,
          )
        )
        registerTrigger(
          new LsuTransferTrafficTrigger(
            triggerContext,
            synchronizerNodeService.nodes.current,
            successorSynchronizerNode,
          )
        )
        registerTrigger(
          new LsuSequencingTestTrigger(
            config,
            triggerContext,
            synchronizerNodeService.nodes.current,
            successorSynchronizerNode,
          )
        )
      case _ => ()
    }
  }

  def registerTrafficReconciliationTriggers(): Unit = {
    registerTrigger(
      new ReconcileSequencerLimitWithMemberTrafficTrigger(
        triggerContext,
        dsoStore,
        synchronizerNodeService,
        config.trafficBalanceReconciliationDelay,
      )
    )
    registerTrigger(
      new SvOnboardingUnlimitedTrafficTrigger(
        onboardingTriggerContext,
        dsoStore,
        synchronizerNodeService,
        config.trafficBalanceReconciliationDelay,
      )
    )
  }

  def registerPostUnlimitedTrafficTriggers(): Unit = {
    registerTrigger(
      new SummarizingMiningRoundTrigger(
        triggerContext,
        dsoStore,
        connection(SpliceLedgerConnectionPriority.Medium),
        () => getOrCreateOwnScanConnection(),
        () => getOrCreatePeerScanConnection(),
      )
    )
    registerTrigger(
      new ReceiveSvRewardCouponTrigger(
        triggerContext,
        dsoStore,
        participantAdminConnection,
        connection(SpliceLedgerConnectionPriority.High),
        config.extraBeneficiaries,
      )
    )
    if (config.automation.enableClosedRoundArchival)
      registerTrigger(
        new ArchiveClosedMiningRoundsTrigger(
          triggerContext,
          dsoStore,
          connection(SpliceLedgerConnectionPriority.Low),
        )
      )

    registerTrigger(
      new CalculateRewardsTrigger(
        triggerContext,
        dsoStore,
        connection(SpliceLedgerConnectionPriority.Medium),
        () => getOrCreateOwnScanConnection(),
        () => getOrCreatePeerScanConnection(),
      )
    )

    registerTrigger(
      new CalculateRewardsDryRunTrigger(
        triggerContext,
        dsoStore,
        connection(SpliceLedgerConnectionPriority.Medium),
        () => getOrCreateOwnScanConnection(),
        () => getOrCreatePeerScanConnection(),
      )
    )

    registerTrigger(
      new ConfirmationMismatchReportTrigger(
        triggerContext,
        dsoStore,
      )
    )

    dsoDelegateBasedAutomation.start()

    registerTrigger(
      new AnsSubscriptionInitialPaymentTrigger(
        triggerContext,
        dsoStore,
        spliceInstanceNamesConfig,
        connection(SpliceLedgerConnectionPriority.Medium),
      )
    )
    registerTrigger(
      new SvPackageVettingTrigger(
        participantAdminConnection,
        dsoStore,
        triggerContext,
        config.maxVettingDelay,
        config.latestPackagesOnly,
        config.parameters.enabledFeatures.enableUnsupportedDarsUnvetting,
        config.additionalPackagesToUnvet,
      )
    )

    // SV status report triggers
    registerTrigger(
      new SubmitSvStatusReportTrigger(
        config,
        triggerContext,
        dsoStore,
        connection(SpliceLedgerConnectionPriority.Medium),
        synchronizerNodeService,
        participantAdminConnection,
      )
    )
    registerTrigger(
      new ReportSvStatusMetricsExportTrigger(
        triggerContext,
        dsoStore,
        synchronizerNodeService,
      )
    )
    registerTrigger(
      new ReportValidatorLicenseMetricsExportTrigger(
        triggerContext,
        dsoStore,
      )
    )
    registerTrigger(
      new TransferCommandCounterTrigger(
        triggerContext,
        dsoStore,
        connection(SpliceLedgerConnectionPriority.Low),
      )
    )
    registerTrigger(
      new AmuletPriceMetricsTrigger(
        triggerContext,
        dsoStore,
      )
    )
    registerTrigger(
      new VoteRequestMetricsTrigger(
        triggerContext,
        dsoStore,
      )
    )
    registerTrigger(
      new RewardMetricsTrigger(
        triggerContext,
        dsoStore,
      )
    )

    registerTrigger(
      new PublishScanConfigTrigger(
        triggerContext,
        dsoStore,
        connection(SpliceLedgerConnectionPriority.Low),
        config.scan,
        upgradesConfig,
      )
    )

    config.followAmuletConversionRateFeed.foreach { c =>
      registerTrigger(
        new FollowAmuletConversionRateFeedTrigger(
          triggerContext,
          dsoStore,
          connection(SpliceLedgerConnectionPriority.Low),
          c,
        )
      )
    }

    config.copyVotesFrom.foreach { svName =>
      registerTrigger(
        new CopyVotesTrigger(
          triggerContext,
          dsoStore,
          connection(SpliceLedgerConnectionPriority.Low),
          svName,
        )
      )
    }
  }

  private val localSequencerClientContext: Option[LocalSequencerClientContext] =
    synchronizerNodeService.nodes.current.some
      .map(cfg =>
        LocalSequencerClientContext(
          cfg.sequencerAdminConnection,
          cfg.mediatorAdminConnection,
          Some(
            LocalSequencerClientConfig(
              cfg.sequencerInternalConfig,
              config.domains.global.alias,
            )
          ),
          cfg.sequencerPruningConfig.map(pruningConfig =>
            SequencerPruningConfig(
              pruningConfig.pruningInterval,
              pruningConfig.retentionPeriod,
            )
          ),
        )
      )

  if (!config.bftSequencerConnection) {
    registerTrigger(
      new LocalSequencerConnectionsTrigger(
        triggerContext,
        participantAdminConnection,
        config.domains.global.alias,
        dsoStore,
        synchronizerNodeService,
        config.participantClient.sequencerRequestAmplification.toInternal,
        config.participantClient.sequencerConnectionPoolDelays.toInternal,
        config.participantClient.subscriptionLivenessLimits.toInternal,
        reconnectOnSynchronizerConfigurationChange =
          enabledFeatures.reconnectOnSynchronizerConfigurationChange,
        useInternalSequencerApi = config.useInternalSequencerApi,
      )
    )
  }

  // fine to run the trigger only for the current sync as after a LSU we don't have anything to prune yet
  localSequencerClientContext.foreach { sequencerContext =>
    sequencerContext.pruningConfig.foreach { pruningConfig =>
      val contextWithSpecificPolling = triggerContext.copy(
        config = triggerContext.config.copy(
          pollingInterval = pruningConfig.pruningInterval
        )
      )
      registerTrigger(
        new SequencerPruningTrigger(
          contextWithSpecificPolling,
          dsoStore,
          config.scan,
          upgradesConfig,
          sequencerContext.sequencerAdminConnection,
          sequencerContext.mediatorAdminConnection,
          clock,
          pruningConfig.retentionPeriod,
          pruningConfig.pruningSafetyCheckPercentage,
          participantAdminConnection,
          dsoStore.domainMigrationId,
          grpcClientMetrics,
        )
      )
    }
  }

  registerTrigger(
    new CreateBootstrapExternalPartyConfigStateInstructionTrigger(
      triggerContext,
      dsoStore,
      connection(SpliceLedgerConnectionPriority.Low),
    )
  )
}

object SvDsoAutomationService extends AutomationServiceCompanion {
  case class LocalSequencerClientContext(
      sequencerAdminConnection: SequencerAdminConnection,
      mediatorAdminConnection: MediatorAdminConnection,
      internalClientConfig: Option[LocalSequencerClientConfig],
      pruningConfig: Option[SequencerPruningConfig] = None,
  )

  case class LocalSequencerClientConfig(
      sequencerInternalConfig: ClientConfig,
      decentralizedSynchronizerAlias: SynchronizerAlias,
  )

  // defined because some triggers are registered later by
  // registerPostOnboardingTriggers
  override protected[this] def expectedTriggerClasses: Seq[TriggerClass] =
    SpliceAppAutomationService.expectedTriggerClasses ++ Seq(
      aTrigger[SummarizingMiningRoundTrigger],
      aTrigger[SvOnboardingRequestTrigger],
      aTrigger[ReceiveSvRewardCouponTrigger],
      aTrigger[ArchiveClosedMiningRoundsTrigger],
      aTrigger[CalculateRewardsTrigger],
      aTrigger[CalculateRewardsDryRunTrigger],
      aTrigger[ConfirmationMismatchReportTrigger],
      aTrigger[AnsSubscriptionInitialPaymentTrigger],
      aTrigger[SvPackageVettingTrigger],
      aTrigger[SvOffboardingPartyToParticipantProposalTrigger],
      aTrigger[SvOffboardingMediatorTrigger],
      aTrigger[SvOnboardingUnlimitedTrafficTrigger],
      aTrigger[SvOffboardingSequencerTrigger],
      aTrigger[ReconcileSequencerLimitWithMemberTrafficTrigger],
      aTrigger[SvNamespaceMembershipTrigger],
      aTrigger[SvClearOnboardingFlagTrigger],
      aTrigger[SvOnboardingPartyToParticipantProposalTrigger],
      aTrigger[SvOnboardingSequencerTrigger],
      aTrigger[SvOnboardingMediatorProposalTrigger],
      aTrigger[PublishLocalCometBftNodeConfigTrigger],
      aTrigger[PublishScanConfigTrigger],
      aTrigger[ReconcileCometBftNetworkConfigWithDsoRulesTrigger],
      aTrigger[LocalSequencerConnectionsTrigger],
      aTrigger[SequencerPruningTrigger],
      aTrigger[SubmitSvStatusReportTrigger],
      aTrigger[ReportSvStatusMetricsExportTrigger],
      aTrigger[ReportValidatorLicenseMetricsExportTrigger],
      aTrigger[ReconcileDynamicSynchronizerParametersTrigger],
      aTrigger[TransferCommandCounterTrigger],
      aTrigger[SvBftSequencerPeerOffboardingTrigger],
      aTrigger[SvBftSequencerPeerOnboardingTrigger],
      aTrigger[FollowAmuletConversionRateFeedTrigger],
      aTrigger[CopyVotesTrigger],
      aTrigger[AmuletPriceMetricsTrigger],
      aTrigger[VoteRequestMetricsTrigger],
      aTrigger[RewardMetricsTrigger],
      aTrigger[CreateBootstrapExternalPartyConfigStateInstructionTrigger],
      aTrigger[LsuTrigger],
      aTrigger[LsuAnnouncementTrigger],
      aTrigger[LsuTransferTrafficTrigger],
      aTrigger[LsuSequencingTestTrigger],
      aTrigger[ReconcileSequencingParametersTrigger],
    )
}
