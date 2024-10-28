package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.metrics.api.noop.NoOpMetricsFactory
import org.lfdecentralizedtrust.splice.admin.api.client.{DamlGrpcClientMetrics, GrpcClientMetrics}
import org.lfdecentralizedtrust.splice.automation.{AmuletConfigReassignmentTrigger, AssignTrigger}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.AmuletConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_AddFutureAmuletConfigSchedule
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.AmuletDecentralizedSynchronizerConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.{
  ARC_AmuletRules,
  ARC_DsoRules,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_AddFutureAmuletConfigSchedule
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.{
  DsoDecentralizedSynchronizerConfig,
  SynchronizerState,
  SynchronizerConfig as DamlSynchronizerConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.SRARC_SetConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  DsoRulesConfig,
  DsoRules_SetConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell as splitwellCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as walletCodegen
import org.lfdecentralizedtrust.splice.config.{ConfigTransforms, SynchronizerConfig}
import org.lfdecentralizedtrust.splice.console.SvAppBackendReference
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  RetryProvider,
  SequencerAdminConnection,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.scan.config.ScanSynchronizerConfig
import org.lfdecentralizedtrust.splice.splitwell.admin.api.client.commands.HttpSplitwellAppClient
import org.lfdecentralizedtrust.splice.splitwell.automation.AcceptedAppPaymentRequestsTrigger
import org.lfdecentralizedtrust.splice.splitwell.config.SplitwellDomains
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.util.{
  Codec,
  ConfigScheduleUtil,
  SplitwellTestUtil,
  SynchronizerFeesTestUtil,
  TriggerTestUtil,
  UpdateHistoryTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.validator.automation.ReconcileSequencerConnectionsTrigger
import com.digitalasset.canton.concurrent.FutureSupervisor
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpireIssuingMiningRoundTrigger,
}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.LocalSequencerConnectionsTrigger
import com.digitalasset.canton.{BaseTest, DomainAlias, SequencerAlias}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.{ClientConfig, NonNegativeDuration, ProcessingTimeout}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.participant.domain.DomainConnectionConfig
import com.digitalasset.canton.sequencing.SequencerConnections
import com.digitalasset.canton.topology.{DomainId, UniqueIdentifier}
import com.digitalasset.canton.topology.store.TopologyStoreId
import org.scalatest.time.{Minute, Span}

import java.time.temporal.ChronoUnit
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class SoftDomainMigrationTopologySetupIntegrationTest
    extends IntegrationTest
    with ConfigScheduleUtil
    with SplitwellTestUtil
    with TriggerTestUtil
    with WalletTestUtil
    with SynchronizerFeesTestUtil
    with UpdateHistoryTestUtil {

  // Does not currently handle multiple synchronizers.
  override def runUpdateHistorySanityCheck = false

  private val splitwellDarPath = "daml/splitwell/.daml/dist/splitwell-current.dar"

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  override def environmentDefinition =
    EnvironmentDefinition
      .fromResources(
        Seq("simple-topology.conf", "simple-topology-soft-domain-upgrade.conf"),
        this.getClass.getSimpleName,
      )
      .withAllocatedUsers()
      .withInitializedNodes()
      .withTrafficTopupsEnabled
      // TODO(#15569): Get rid of this once we retry transfer offer creation for this test
      .withTrafficBalanceCacheDisabled
      .addConfigTransformsToFront(
        (_, conf) =>
          ConfigTransforms.updateAllSvAppConfigs_ { conf =>
            val synchronizerConfig = conf.localSynchronizerNode.value
            conf.copy(
              synchronizerNodes = Map(
                "global-domain" -> synchronizerConfig,
                "global-domain-new" -> ConfigTransforms
                  .setSvSynchronizerConfigPortsPrefix(28, synchronizerConfig),
              ),
              supportsSoftDomainMigrationPoc = true,
            )
          }(conf),
        (_, conf) =>
          ConfigTransforms.updateAllScanAppConfigs { (name, scanConf) =>
            val svConf = conf.svApps(InstanceName.tryCreate(name.stripSuffix("Scan")))
            scanConf.copy(
              synchronizers = svConf.synchronizerNodes.view
                .mapValues(c =>
                  ScanSynchronizerConfig(
                    c.sequencer.adminApi,
                    c.mediator.adminApi,
                  )
                )
                .toMap,
              supportsSoftDomainMigrationPoc = true,
            )
          }(conf),
        (_, conf) =>
          ConfigTransforms.updateAllValidatorAppConfigs_(c =>
            c.copy(
              supportsSoftDomainMigrationPoc = true
            )
          )(conf),
        (_, conf) =>
          ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Sv)(
            _.withResumedTrigger[AmuletConfigReassignmentTrigger]
              .withResumedTrigger[AssignTrigger]
              .withPausedTrigger[AdvanceOpenMiningRoundTrigger]
              .withPausedTrigger[ExpireIssuingMiningRoundTrigger]
          )(conf),
        (_, conf) =>
          ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
            _.copy(initialTickDuration = NonNegativeFiniteDuration.ofMillis(500))
          )(conf),
        (_, conf) =>
          // At least for now we test the impact of soft domain migrations on an app
          // running on the global synchronizer not on a separate synchronizer
          ConfigTransforms.updateAllSplitwellAppConfigs_(c => {
            c.copy(
              domains = c.domains.copy(
                splitwell = SplitwellDomains(
                  SynchronizerConfig(DomainAlias.tryCreate("global-global-domain")),
                  Seq.empty,
                )
              ),
              supportsSoftDomainMigrationPoc = true,
            )
          })(conf),
      )
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })

  "SVs can bootstrap new domain" in { implicit env =>
    implicit val ec: ExecutionContext = env.executionContext
    val ledgerBeginSv1 = sv1Backend.participantClient.ledger_api.state.end()
    val (alice, bob) = onboardAliceAndBob()
    env.scans.local should have size 4
    val prefix = "global-domain-new"
    clue("DSO info has 4 synchronizer nodes") {
      eventually() {
        val dsoInfo = sv1Backend.getDsoInfo()
        dsoInfo.svNodeStates.values.flatMap(
          _.payload.state.synchronizerNodes.asScala.values.flatMap(_.scan.toScala)
        ) should have size 4
      }
    }
    clue("All synchronizer nodes are initialized") {
      env.svs.local.foreach { sv =>
        waitForSynchronizerInitialized(sv)
      }
    }
    // Enough time that the voting flow can go through
    val scheduledTime = env.environment.clock.now.plus(java.time.Duration.ofSeconds(30)).toInstant
    val amuletConfig =
      sv1ScanBackend.getAmuletRules().payload.configSchedule.initialValue
    val existingDomainId =
      Codec.tryDecode(Codec.DomainId)(amuletConfig.decentralizedSynchronizer.activeSynchronizer)
    val newDomainId = DomainId(UniqueIdentifier.tryCreate(prefix, existingDomainId.uid.namespace))
    val newAmuletConfig =
      new AmuletConfig(
        amuletConfig.transferConfig,
        amuletConfig.issuanceCurve,
        new AmuletDecentralizedSynchronizerConfig(
          new org.lfdecentralizedtrust.splice.codegen.java.da.set.types.Set(
            (amuletConfig.decentralizedSynchronizer.requiredSynchronizers.map.asScala.toMap + (newDomainId.toProtoPrimitive -> com.daml.ledger.javaapi.data.Unit
              .getInstance())).asJava
          ),
          newDomainId.toProtoPrimitive,
          amuletConfig.decentralizedSynchronizer.fees,
        ),
        amuletConfig.tickDuration,
        amuletConfig.packageConfig,
        java.util.Optional.empty(),
      )

    // tap before the migration
    aliceWalletClient.tap(100)

    val group = "group1"
    val groupKey = HttpSplitwellAppClient.GroupKey(
      group,
      alice,
    )
    // TODO(#14419) Remove this once the retries cover all required errors
    setTriggersWithin(triggersToPauseAtStart =
      Seq(aliceValidatorBackend, bobValidatorBackend, splitwellValidatorBackend).map(
        _.validatorAutomation.trigger[ReconcileSequencerConnectionsTrigger]
      )
    ) {
      clue("Setup splitwell") {
        Seq((aliceSplitwellClient, alice), (bobSplitwellClient, bob)).foreach {
          case (splitwell, party) =>
            createSplitwellInstalls(splitwell, party)
        }
        actAndCheck("create 'group1'", aliceSplitwellClient.requestGroup(group))(
          "Alice sees 'group1'",
          _ => aliceSplitwellClient.listGroups() should have size 1,
        )

        // Wait for the group contract to be visible to Alice's Ledger API
        aliceSplitwellClient.ledgerApi.ledger_api_extensions.acs
          .awaitJava(splitwellCodegen.Group.COMPANION)(alice)

        val (_, invite) = actAndCheck(
          "create a generic invite for 'group1'",
          aliceSplitwellClient.createGroupInvite(
            group
          ),
        )(
          "alice observes the invite",
          _ => aliceSplitwellClient.listGroupInvites().loneElement.toAssignedContract.value,
        )

        actAndCheck("bob asks to join 'group1'", bobSplitwellClient.acceptInvite(invite))(
          "Alice sees the accepted invite",
          _ => aliceSplitwellClient.listAcceptedGroupInvites(group) should not be empty,
        )

        actAndCheck(
          "bob joins 'group1'",
          inside(aliceSplitwellClient.listAcceptedGroupInvites(group)) { case Seq(accepted) =>
            aliceSplitwellClient.joinGroup(accepted.contractId)
          },
        )(
          "bob is in 'group1'",
          _ => {
            bobSplitwellClient.listGroups() should have size 1
            aliceSplitwellClient.listAcceptedGroupInvites(group) should be(empty)
          },
        )

      }
    }

    val (_, voteRequest) = actAndCheck(
      "Creating amulet config vote request",
      eventuallySucceeds() {
        sv1Backend.createVoteRequest(
          sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
          new ARC_AmuletRules(
            new CRARC_AddFutureAmuletConfigSchedule(
              new AmuletRules_AddFutureAmuletConfigSchedule(
                new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
                  scheduledTime,
                  newAmuletConfig,
                )
              )
            )
          ),
          "url",
          "description",
          sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
        )
      },
    )("amulet config vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)

    // TODO(#8300) No need to pause once we can't get a timeout on a concurrent sequencer connection change anymore
    setTriggersWithin(triggersToPauseAtStart =
      Seq(sv2Backend, sv3Backend, sv4Backend).map(
        _.dsoAutomation.trigger[LocalSequencerConnectionsTrigger]
      )
    ) {
      clue(s"sv2-4 accept amulet config vote request") {
        Seq(sv2Backend, sv3Backend, sv4Backend).map(sv =>
          eventuallySucceeds() {
            sv.castVote(
              voteRequest.contractId,
              true,
              "url",
              "description",
            )
          }
        )
      }
    }

    eventually() {
      sv1ScanBackend.getAmuletRules().payload.configSchedule.futureValues should not be empty
    }

    val dsoRules = sv1Backend.getDsoInfo().dsoRules

    clue("Bootstrap new domain") {
      clue("Sign bootstrapping state") {
        val signed = env.svs.local.map { sv =>
          Future { sv.signSynchronizerBootstrappingState(prefix) }
        }
        signed.foreach(_.futureValue)
      }
      clue("Initialize synchronizer nodes") {
        val initialized = env.svs.local.map { sv =>
          Future { sv.initializeSynchronizer(prefix) }
        }
        initialized.foreach(_.futureValue)
      }
      clue("New synchronizer is registered in DsoRules config") {
        val (_, dsoRulesVoteRequest) = actAndCheck(
          "Creating dso rules config vote request",
          eventuallySucceeds() {
            sv1Backend.createVoteRequest(
              sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
              new ARC_DsoRules(
                new SRARC_SetConfig(
                  new DsoRules_SetConfig(
                    new DsoRulesConfig(
                      dsoRules.payload.config.numUnclaimedRewardsThreshold,
                      dsoRules.payload.config.numMemberTrafficContractsThreshold,
                      dsoRules.payload.config.actionConfirmationTimeout,
                      dsoRules.payload.config.svOnboardingRequestTimeout,
                      dsoRules.payload.config.svOnboardingConfirmedTimeout,
                      dsoRules.payload.config.voteRequestTimeout,
                      dsoRules.payload.config.dsoDelegateInactiveTimeout,
                      dsoRules.payload.config.synchronizerNodeConfigLimits,
                      dsoRules.payload.config.maxTextLength,
                      new DsoDecentralizedSynchronizerConfig(
                        (dsoRules.payload.config.decentralizedSynchronizer.synchronizers.asScala.toMap + (newDomainId.toProtoPrimitive -> new DamlSynchronizerConfig(
                          SynchronizerState.DS_OPERATIONAL,
                          // Keep the cometbft state empty, we don't support bootstrapping the new domain with cometbft.
                          "",
                          dsoRules.payload.config.decentralizedSynchronizer.synchronizers.values.loneElement.acsCommitmentReconciliationInterval,
                        ))).asJava,
                        newDomainId.toProtoPrimitive,
                        newDomainId.toProtoPrimitive,
                      ),
                      dsoRules.payload.config.nextScheduledSynchronizerUpgrade,
                    )
                  )
                )
              ),
              "url",
              "description",
              sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
            )
          },
        )(
          "dsorules config vote request has been created",
          _ => sv1Backend.listVoteRequests().loneElement,
        )
        clue(s"sv2-4 accept dsorules config vote request") {
          Seq(sv2Backend, sv3Backend, sv4Backend).map(sv =>
            eventuallySucceeds() {
              sv.castVote(
                dsoRulesVoteRequest.contractId,
                true,
                "url",
                "description",
              )
            }
          )
        }
      }
      clue("Reconcile Daml synchronizer state") {
        val reconciled = env.svs.local.map { sv =>
          Future { sv.reconcileSynchronizerDamlState(prefix) }
        }
        reconciled.foreach(_.futureValue)
      }
    }
    val domainAlias = DomainAlias.tryCreate(prefix)
    clue("SV participants connect to new domain") {
      env.svs.local.map { sv =>
        val participant = sv.participantClient
        val connection =
          LocalSynchronizerNode.toSequencerConnection(
            sv.config.synchronizerNodes(prefix).sequencer.internalApi,
            SequencerAlias.tryCreate(sv.config.onboarding.value.name),
          )
        participant.domains.register_with_config(
          DomainConnectionConfig(
            domainAlias,
            SequencerConnections.single(connection),
          ),
          handshakeOnly = false,
        )
      }
    }
    actAndCheck(
      "Sign DSO PartyToParticipant mapping", {
        env.svs.local.foreach { sv =>
          sv.signDsoPartyToParticipant(prefix)
        }
      },
    )(
      "DSO PartyToParticipant is updated on domain",
      _ => {
        sv1Backend.participantClient.topology.party_to_participant_mappings
          .list(newDomainId, filterParty = dsoRules.payload.dso) should not be empty
      },
    )

    // Ensure that the scheduled time has passed.
    // This is mainly to avoid putting a stupidly large time in the eventually below.
    clue("Waiting for scheduled time") {
      env.environment.clock
        .scheduleAt(
          _ => (),
          CantonTimestamp.assertFromInstant(scheduledTime.plus(500, ChronoUnit.MILLIS)),
        )
        .unwrap
        .futureValue
    }

    // It takes a pretty long time until the SVs vet the packages on the new domain
    // and the reassignments go through.
    eventually(40.seconds) {
      val amuletRules = sv1ScanBackend.getAmuletRules()
      inside(amuletRules) { case _ =>
        amuletRules.state shouldBe ContractState.Assigned(newDomainId)
      }
      val (openRounds, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
      forAll(openRounds) { round =>
        round.state shouldBe ContractState.Assigned(newDomainId)
      }
      forAll(issuingRounds) { round =>
        round.state shouldBe ContractState.Assigned(newDomainId)
      }
    }

    clue("Round can be advanced") {
      advanceRoundsByOneTickViaAutomation(BaseTest.DefaultEventuallyTimeUntilSuccess * 2)
    }
    eventually() {
      sv1ScanBackend.getAmuletRules().state shouldBe ContractState.Assigned(newDomainId)
      sv1ScanBackend.getDsoInfo().dsoRules.domainId.value shouldBe newDomainId.toProtoPrimitive
    }

    clue("Alice validator tops up its traffic on new domain") {
      eventually(1.minute) {
        val topupAmount =
          getTopupParameters(aliceValidatorBackend, env.environment.clock.now).topupAmount
        aliceValidatorBackend.participantClient.traffic_control
          .traffic_state(newDomainId)
          .extraTrafficPurchased
          .value shouldBe topupAmount
      }
    }
    clue("All SV participants have unlimited traffic on new domain") {
      eventually() {
        env.svs.local.foreach { sv =>
          val participantId = sv.participantClient.id
          clue(s"participant $participantId has unlimited traffic on new domain") {
            sv1Backend
              .sequencerClient(newDomainId)
              .traffic_control
              .last_traffic_state_update_of_members(
                Seq(participantId)
              )
              .trafficStates
              .values
              .loneElement
              .extraTrafficPurchased shouldBe NonNegativeLong.maxValue
          }
        }
      }
    }
    clue("All mediators have unlimited traffic on new domain") {
      eventually() {
        val mediatorState = sv1Backend.participantClient.topology.mediators
          .list(filterStore = TopologyStoreId.DomainStore(newDomainId).filterName)
          .loneElement
        val mediators = mediatorState.item.active.forgetNE
        mediators should have size 4
        forAll(mediators) { mediator =>
          sv1Backend
            .sequencerClient(newDomainId)
            .traffic_control
            .last_traffic_state_update_of_members(
              Seq(mediator)
            )
            .trafficStates
            .values
            .loneElement
            .extraTrafficPurchased shouldBe NonNegativeLong.maxValue
        }
      }
    }

    p2pTransfer(
      aliceWalletClient,
      bobWalletClient,
      bob,
      42.0,
      timeUntilSuccess = 40.seconds,
    )

    val aliceAmulets = aliceWalletClient.list().amulets
    aliceAmulets should not be empty
    forAll(aliceAmulets) {
      _.contract.state shouldBe ContractState.Assigned(newDomainId)
    }

    // Eventually to allow merging to also reassign
    // any other contracts.
    eventually() {
      val bobAmulets = bobWalletClient.list().amulets
      bobAmulets should not be empty
      forAll(bobAmulets) {
        _.contract.state shouldBe ContractState.Assigned(newDomainId)
      }
    }

    val (_, paymentRequest) =
      actAndCheck(
        "alice initiates transfer",
        aliceSplitwellClient.initiateTransfer(
          groupKey,
          Seq(
            new walletCodegen.ReceiverAmuletAmount(
              bob.toProtoPrimitive,
              BigDecimal(10.0).bigDecimal,
            )
          ),
        ),
      )(
        "alice sees payment request",
        _ => {
          aliceWalletClient.listAppPaymentRequests().loneElement
        },
      )

    splitwellBackend.splitwellAutomation
      .trigger[AcceptedAppPaymentRequestsTrigger]
      .pause()
      .futureValue
    actAndCheck(
      "Alice accepts payment request",
      aliceWalletClient.acceptAppPaymentRequest(paymentRequest.contractId),
    )(
      "alice observers accepted app payment request",
      _ => {
        aliceWalletClient.listAcceptedAppPayments().loneElement.state shouldBe ContractState
          .Assigned(newDomainId)
      },
    )
    splitwellBackend.splitwellAutomation.trigger[AcceptedAppPaymentRequestsTrigger].resume()
    eventually() {
      aliceWalletClient.listAcceptedAppPayments() shouldBe empty
      val balanceUpdates = aliceSplitwellClient.listBalanceUpdates(groupKey)
      balanceUpdates.loneElement.state shouldBe ContractState.Assigned(newDomainId)
    }

    clue("Compare Scan UpdateHistory to the ledger API") {
      eventually() {
        compareHistoryViaScanApi(
          ledgerBeginSv1,
          sv1Backend,
          scancl("sv1ScanClient"),
        )
      }
    }

  }

  def waitForSynchronizerInitialized(
      sv: SvAppBackendReference
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    import env.environment.scheduler
    import env.executionContext
    val grpcClientMetrics: GrpcClientMetrics = new DamlGrpcClientMetrics(
      NoOpMetricsFactory,
      "testing",
    )
    val retryProvider = new RetryProvider(
      loggerFactory,
      ProcessingTimeout(),
      new FutureSupervisor.Impl(NonNegativeDuration.tryFromDuration(10.seconds)),
      NoOpMetricsFactory,
    )
    val loggerFactoryWithKey = loggerFactory.append("synchronizer", sv.name)
    val sequencerAdminConnection = new SequencerAdminConnection(
      ClientConfig(port = sv.config.localSynchronizerNode.value.sequencer.adminApi.port),
      env.environment.config.monitoring.logging.api,
      loggerFactoryWithKey,
      grpcClientMetrics,
      retryProvider,
    )
    val mediatorAdminConnection = new MediatorAdminConnection(
      ClientConfig(port = sv.config.localSynchronizerNode.value.mediator.adminApi.port),
      env.environment.config.monitoring.logging.api,
      loggerFactoryWithKey,
      grpcClientMetrics,
      retryProvider,
    )

    eventually() {
      sequencerAdminConnection.isNodeInitialized().futureValue shouldBe true
      mediatorAdminConnection.isNodeInitialized().futureValue shouldBe true
    }

    sequencerAdminConnection.close()
    mediatorAdminConnection.close()
    retryProvider.close()
  }
}
