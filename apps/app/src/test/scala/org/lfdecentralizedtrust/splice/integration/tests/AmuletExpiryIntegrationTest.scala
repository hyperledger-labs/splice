package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.logging.SuppressionRule
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.store.db.DbMultiDomainAcsStore
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpiredAmuletTrigger,
  ExpiredLockedAmuletTrigger,
}
import org.lfdecentralizedtrust.splice.util.*
import org.slf4j.event.Level

import java.time.Duration

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_9
class AmuletExpiryIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with TimeTestUtil
    with SplitwellTestUtil
    with TriggerTestUtil {

  // The direct creation of (Locked)Amulet is no appreciated by history parsers
  override protected def runTokenStandardCliSanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      // TODO(#2885): switch to a 4 SV topology once we have contention avoidance in place
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(config)
      )
      // Start rounds trigger in paused state
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(sv => {
          sv.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
        })(config)
      )
      // disable the reward collection to avoid accidental creation or merging of amulet
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.copy(enableAutomaticRewardsCollectionAndAmuletMerging = false)
        )(config)
      )
      // Make batches for amulet expiry trigger smaller, so we see the logic working
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy(
            delegatelessAutomationExpiredAmuletBatchSize = 2
          )
        )(config)
      )

  "Amulet should expire" in { implicit env =>
    val sv1UserId = sv1WalletClient.config.ledgerApiUser
    val sv1UserParty = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)

    val numAmulets = 10
    val amuletAmount = BigDecimal(123.0)

    loggerFactory.suppress(
      SuppressionRule.forLogger[DbMultiDomainAcsStore[?]] && SuppressionRule.Level(Level.ERROR)
    )(
      actAndCheck(
        "Create locked and unlocked dust amulet", {
          for (i <- 1 to numAmulets) {
            createAmulet(
              sv1ValidatorBackend.participantClientWithAdminToken,
              sv1UserId,
              sv1UserParty,
              amount = amuletAmount,
              holdingFee = amuletAmount,
            )
            createLockedAmulet(
              sv1ValidatorBackend.participantClientWithAdminToken,
              sv1UserId,
              sv1UserParty,
              lockHolders = Seq(sv1UserParty),
              amount = amuletAmount,
              holdingFee = amuletAmount,
              expiredDuration = Duration.ofSeconds(1),
            )
          }
        },
      )(
        "Locked and unlocked dust amulet is created",
        _ => {
          sv1WalletClient.list().amulets should have length numAmulets.toLong
          sv1WalletClient.list().lockedAmulets should have length numAmulets.toLong
        },
      )
    )

    actAndCheck(
      "Advance 4 rounds and turn on triggers", {
        advanceRoundsByOneTickViaAutomation()
        advanceRoundsByOneTickViaAutomation()
        advanceRoundsByOneTickViaAutomation()
        advanceRoundsByOneTickViaAutomation()

        env.svs.local.foreach(sv => {
          sv.dsoDelegateBasedAutomation.trigger[ExpiredAmuletTrigger].resume()
          sv.dsoDelegateBasedAutomation.trigger[ExpiredLockedAmuletTrigger].resume()
        })
      },
    )(
      "Check that dust amulet gets expired",
      _ => {
        sv1WalletClient.list().amulets shouldBe empty
        sv1WalletClient.list().lockedAmulets shouldBe empty
      },
    )
  }
}
