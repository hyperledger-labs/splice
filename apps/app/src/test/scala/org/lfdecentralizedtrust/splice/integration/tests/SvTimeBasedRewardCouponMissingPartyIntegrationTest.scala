package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import org.lfdecentralizedtrust.splice.sv.config.BeneficiaryConfig
import org.lfdecentralizedtrust.splice.util.{TriggerTestUtil, WalletTestUtil}
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import monocle.macros.syntax.lens.*
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.slf4j.event.Level

import scala.math.Ordering.Implicits.*

class SvTimeBasedRewardCouponMissingPartyIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with SvTimeBasedIntegrationTestUtil
    with WalletTestUtil
    with WalletTxLogTestUtil
    with TriggerTestUtil {

  override protected def runUpdateHistorySanityCheck: Boolean = false
  override protected def runTokenStandardCliSanityCheck: Boolean = false
  val badPartyHint = "badpartyhint"

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[ReceiveSvRewardCouponTrigger]
        )(config)
      )
      .addConfigTransform((_, config) => {
        config
          .focus(_.svApps)
          .modify(_.map { case (name, svConfig) =>
            // Setting the beneficiary party ID to a value that will never be in the party to participant mapping
            val newConfig = if (name.unwrap == "sv1") {
              val aliceParticipant =
                ConfigTransforms
                  .getParticipantIds(config.parameters.clock)("alice_validator_user")

              val alicePartyId = PartyId
                .tryFromProtoPrimitive(
                  s"$badPartyHint::${aliceParticipant.split("::").last}"
                )
              svConfig
                .copy(extraBeneficiaries =
                  Seq(BeneficiaryConfig(alicePartyId, NonNegativeLong.tryCreate(3333L)))
                )
            } else svConfig

            name -> newConfig
          })
      })

  "SVs" should {
    "filter out beneficiaries that were not found in party to participant mapping" in {
      implicit env =>
        val sv1RewardCouponTrigger = sv1Backend.dsoAutomation.trigger[ReceiveSvRewardCouponTrigger]
        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          within = {

            eventually() {
              clue("No SvRewardCoupon should be issued") {
                advanceRoundsToNextRoundOpening
                sv1RewardCouponTrigger.runOnce().futureValue
                eventually() {
                  val openRounds = sv1ScanBackend
                    .getOpenAndIssuingMiningRounds()
                    ._1
                    .filter(_.payload.opensAt <= env.environment.clock.now.toInstant)
                  openRounds should not be empty
                }
              }
            }
          },
          lines => {
            forAtLeast(1, lines) { entry =>
              entry.message should include("Beneficiaries did not vet the latest packages")
            }
            forAtLeast(1, lines) { entry =>
              entry.message should include(
                "Party to participant mapping not found for synchronizer"
              )
              entry.message should include(badPartyHint)
            }
          },
        )
    }
  }
}
