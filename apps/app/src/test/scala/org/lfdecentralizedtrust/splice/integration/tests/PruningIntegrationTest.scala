package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.admin.api.client.data.PruningSchedule
import org.lfdecentralizedtrust.splice.config.{
  ConfigTransforms,
  ParticipantClientConfig,
  PruningConfig,
}
import org.lfdecentralizedtrust.splice.console.ValidatorAppBackendReference
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SequencerPruningTrigger
import org.lfdecentralizedtrust.splice.sv.config.SequencerPruningConfig
import org.lfdecentralizedtrust.splice.util.{ProcessTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.automation.ReconcileSequencerConnectionsTrigger
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.{
  FullClientConfig,
  NonNegativeFiniteDuration,
  PositiveDurationSeconds,
}
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.util.ShowUtil.*
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority.Low
import org.slf4j.event.Level

import scala.concurrent.duration.*

class PruningIntegrationTest
    extends SvIntegrationTestBase
    with WalletTestUtil
    with ProcessTestUtil {

  override protected def runEventHistorySanityCheck: Boolean = false

  override def environmentDefinition
      : org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition =
    super.environmentDefinition
      .addConfigTransforms(
        (_, config) =>
          ConfigTransforms.updateAllSvAppConfigs { (_, config) =>
            config.copy(
              localSynchronizerNode = config.localSynchronizerNode.map(synchronizerNode =>
                synchronizerNode.copy(
                  sequencer = synchronizerNode.sequencer.copy(
                    pruning = Some(
                      SequencerPruningConfig(
                        pruningInterval = NonNegativeFiniteDuration(2.seconds),
                        retentionPeriod = NonNegativeFiniteDuration(120.seconds),
                      )
                    )
                  )
                )
              ),
              // The pruning trigger only registers the advancing of time when things happen
              // on the ledger, so let's make sure that things happen frequently.
              onLedgerStatusReportInterval = NonNegativeFiniteDuration(30.seconds),
            )
          }(config),
        (_, config) =>
          ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Sv)(
            _.withPausedTrigger[SequencerPruningTrigger]
          )(config),
        (_, config) =>
          config.copy(
            svApps = config.svApps.updatedWith(InstanceName.tryCreate("sv1")) {
              _.map { config =>
                config.copy(acsCommitmentReconciliationInterval =
                  PositiveDurationSeconds.ofSeconds(15)
                )
              }
            },
            validatorApps =
              config.validatorApps.updatedWith(InstanceName.tryCreate("sv1Validator")) {
                _.map { config =>
                  config.copy(
                    // schedule needs to be defined to activate participant pruning
                    participantPruningSchedule = Some(
                      PruningConfig(
                        "0 /1 * * * ?",
                        PositiveDurationSeconds.tryFromDuration(2.seconds),
                        PositiveDurationSeconds.tryFromDuration(5.seconds),
                      )
                    )
                  )
                }
              } + (
                InstanceName.tryCreate("bobValidatorLocal") -> {
                  val bobValidatorConfig = config
                    .validatorApps(InstanceName.tryCreate("bobValidator"))
                  bobValidatorConfig
                    .copy(
                      participantClient = ParticipantClientConfig(
                        FullClientConfig(port = Port.tryCreate(5902)),
                        bobValidatorConfig.participantClient.ledgerApi.copy(
                          clientConfig =
                            bobValidatorConfig.participantClient.ledgerApi.clientConfig.copy(
                              port = Port.tryCreate(5901)
                            )
                        ),
                      ),
                      // We disable the ReconcileSequencerConnectionsTrigger to prevent domain disconnections
                      // from interfering with traffic top-ups (see #14474)
                      automation = bobValidatorConfig.automation
                        .withPausedTrigger[ReconcileSequencerConnectionsTrigger],
                    )
                }
              ),
          ),
      )

  "participant can be pruned" should {

    "when configured, sv1 participant prunes every minute" in { implicit env =>
      initDsoWithSv1Only()

      clue("Check sv1 participant has the expected smallest pruning schedule") {
        sv1ValidatorBackend.participantClient.pruning.get_schedule() shouldBe Some(
          PruningSchedule(
            "0 /1 * * * ?",
            PositiveDurationSeconds.tryFromDuration(2.seconds),
            PositiveDurationSeconds.tryFromDuration(5.seconds),
          )
        )
      }

      eventually(timeUntilSuccess = 70.seconds) {
        sv1Backend.svAutomation
          .connection(Low)
          // returns 0 when participant pruning is disabled
          .latestPrunedOffset()
          .futureValue should be > 0L
      }
    }
  }

  "sequencer can be pruned even if a participant is down" in { implicit env =>
    clue("Initialize DSO with 2 SVs") {
      startAllSync(
        sv1ScanBackend,
        sv2ScanBackend,
        sv1Backend,
        sv2Backend,
        sv1ValidatorBackend,
        sv2ValidatorBackend,
      )
    }
    sv1Backend.getDsoInfo().dsoRules.payload.svs should have size 2
    val bobValidatorLocalBackend: ValidatorAppBackendReference = v("bobValidatorLocal")

    val unavailableParticipantId = withCanton(
      Seq(
        testResourcesPath / "unavailable-validator-topology-canton.conf"
      ),
      Seq(),
      "stop-validator-before-pruning-sequencer",
      "VALIDATOR_ADMIN_USER" -> bobValidatorLocalBackend.config.ledgerApiUser,
    ) {
      startAllSync(bobValidatorLocalBackend)
      val walletUserParty = onboardWalletUser(bobWalletClient, bobValidatorLocalBackend)
      bobWalletClient.tap(walletAmuletToUsd(50.0))
      clue(s"${bobValidatorLocalBackend.name} has tapped a amulet") {
        checkWallet(walletUserParty, bobWalletClient, Seq((50, 50)))
      }
      bobValidatorLocalBackend.participantClientWithAdminToken.health.status.isActive shouldBe Some(
        true
      )
      val participantId = bobValidatorLocalBackend.participantClientWithAdminToken.id
      bobValidatorLocalBackend.stop()
      participantId
    }

    // We only start the sequencer pruning triggers here to make sure that our own nodes are likely to
    // have produced their first acknowledgement.
    // This is slightly racy but checking their last acknowledgement explicitly is a bit awkward and
    // this should be good enough for tests.
    Seq(sv1Backend, sv2Backend).foreach { sv =>
      sv.dsoAutomation.trigger[SequencerPruningTrigger].resume()
    }

    loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.DEBUG))(
      {},
      entries => {
        forAtLeast(2, entries)(
          // we will see that the sequencer is pruned
          _.message should include regex (
            "Completed pruning our sequencer with result: Removed [^0]"
          )
        )
        forAtLeast(2, entries) { entry =>
          // the unavailable validator is disabled
          entry.message should include regex "disabling .+ member clients preventing pruning to"
          entry.message should include regex show"LaggingMember\\(member = $unavailableParticipantId"
        }
      },
      timeUntilSuccess = 3.minutes,
    )
  }

}
