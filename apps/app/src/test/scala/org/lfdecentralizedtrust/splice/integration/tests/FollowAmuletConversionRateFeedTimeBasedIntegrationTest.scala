package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.amuletconversionratefeed.AmuletConversionRateFeed
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.sv.config.*
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.FollowAmuletConversionRateFeedTrigger
import org.lfdecentralizedtrust.splice.util.{
  DisclosedContracts,
  SvTestUtil,
  TimeTestUtil,
  WalletTestUtil,
}
import scala.jdk.OptionConverters.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmuletNameService_0_1_15
class FollowAmuletConversionRateFeedTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with SvTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms(
        (_, config) => {
          val aliceParticipant =
            ConfigTransforms
              .getParticipantIds(config.parameters.clock)("alice_validator_user")
          val alicePartyHint =
            config.validatorApps(InstanceName.tryCreate("aliceValidator")).validatorPartyHint.value
          val alicePartyId = PartyId
            .tryFromProtoPrimitive(
              s"${alicePartyHint}::${aliceParticipant.split("::").last}"
            )
          ConfigTransforms.updateAllSvAppConfigs_(
            _.copy(
              followAmuletConversionRateFeed = Some(
                AmuletConversionRateFeedConfig(
                  alicePartyId,
                  RangeConfig(
                    min = BigDecimal("0.01"),
                    max = BigDecimal("100.0"),
                  ),
                )
              )
            )
          )(config)
        },
        // Pause so we can avoid warnings before we start log suppression
        (_, config) =>
          ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Sv)(
            _.withPausedTrigger[FollowAmuletConversionRateFeedTrigger]
          )(config),
      )

  "SV app follows amulet conversion rate feed" in { implicit env =>
    def runTrigger =
      sv1Backend.dsoAutomation.trigger[FollowAmuletConversionRateFeedTrigger].runOnce().futureValue

    val publisher = aliceValidatorBackend.getValidatorPartyId()

    loggerFactory.assertLogs(
      runTrigger,
      _.warningMessage should include(
        "No AmuletConversionRateFeed for publisher alice"
      ),
    )

    val (feed, _) = actAndCheck(
      "publish price of 42.0", {
        val feed =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = aliceValidatorBackend.config.ledgerApiUser,
              actAs = Seq(publisher),
              readAs = Seq.empty,
              update = new AmuletConversionRateFeed(
                publisher.toProtoPrimitive,
                dsoParty.toProtoPrimitive,
                java.util.Optional.empty(),
                BigDecimal(42.0).bigDecimal,
              ).create,
            )

        // Advance time to be after the vote cooldown from the price vote setup as part of bootstrapping the network.
        advanceTime(java.time.Duration.ofMinutes(2))
        feed
      },
    )(
      "SV updates price to 42.0",
      _ => {
        // deliberately in the eventually as the first run may not yet see the updated feed
        runTrigger
        BigDecimal(
          sv1Backend.listAmuletPriceVotes().loneElement.payload.amuletPrice.toScala.value
        ) shouldBe BigDecimal(42.0)
      },
    )

    val amuletRules = sv1ScanBackend.getAmuletRules()

    val (feed2, _) = actAndCheck(
      "publish price of 23.0", {
        val feed2 =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = aliceValidatorBackend.config.ledgerApiUser,
              actAs = Seq(publisher),
              readAs = Seq.empty,
              update = feed.contractId.exerciseAmuletConversionRateFeed_Update(
                BigDecimal(23.0).bigDecimal,
                amuletRules.contractId,
                java.util.Optional.empty(),
                env.environment.clock.now.plusSeconds(6 * 60).toInstant,
              ),
              disclosedContracts =
                DisclosedContracts.forTesting(amuletRules).toLedgerApiDisclosedContracts,
            )
        runTrigger
        feed2
      },
    )(
      "SV does not update as cooldown has not passed",
      // Note that the assertion here is imprecise, it could also be that the SV just hasn't ingested the feed update yet.
      // We accept that imprecision.
      _ => {
        BigDecimal(
          sv1Backend.listAmuletPriceVotes().loneElement.payload.amuletPrice.toScala.value
        ) shouldBe BigDecimal(42.0)
      },
    )
    actAndCheck(
      "Advance time past cooldown", {
        // For some reason 1 minute + 1 second sometimes seems to not be quite enough due to simtime
        // weirdness not worth investigating.
        advanceTime(java.time.Duration.ofMinutes(1).plus(java.time.Duration.ofSeconds(10)))
      },
    )(
      "SV updates price to 23.0",
      _ => {
        runTrigger
        BigDecimal(
          sv1Backend.listAmuletPriceVotes().loneElement.payload.amuletPrice.toScala.value
        ) shouldBe BigDecimal(23.0)
      },
    )
    // Publish a conversion rate outside the configured bounds
    advanceTime(java.time.Duration.ofMinutes(6))
    val (feed3, _) = actAndCheck(
      "publish price of 200.0",
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          userId = aliceValidatorBackend.config.ledgerApiUser,
          actAs = Seq(publisher),
          readAs = Seq.empty,
          update = feed2.exerciseResult.cid.exerciseAmuletConversionRateFeed_Update(
            BigDecimal(200.0).bigDecimal,
            amuletRules.contractId,
            java.util.Optional.empty(),
            env.environment.clock.now.plusSeconds(6 * 60).toInstant,
          ),
          disclosedContracts =
            DisclosedContracts.forTesting(amuletRules).toLedgerApiDisclosedContracts,
        ),
    )(
      "SV rejects as it is out of the range",
      _ => {
        loggerFactory.assertLogs(
          runTrigger,
          _.warningMessage should include(
            "200.0000000000 which is outside of the configured accepted range RangeConfig(0.01,100.0), clamping to 100.0"
          ),
        )
        BigDecimal(
          sv1Backend.listAmuletPriceVotes().loneElement.payload.amuletPrice.toScala.value
        ) shouldBe BigDecimal(100.0)
      },
    )
    // Advance below the configured bound
    advanceTime(java.time.Duration.ofMinutes(6))
    actAndCheck(
      "publish price of 0.001", {
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitWithResult(
            userId = aliceValidatorBackend.config.ledgerApiUser,
            actAs = Seq(publisher),
            readAs = Seq.empty,
            update = feed3.exerciseResult.cid.exerciseAmuletConversionRateFeed_Update(
              BigDecimal(0.001).bigDecimal,
              amuletRules.contractId,
              java.util.Optional.empty(),
              env.environment.clock.now.plusSeconds(6 * 60).toInstant,
            ),
            disclosedContracts =
              DisclosedContracts.forTesting(amuletRules).toLedgerApiDisclosedContracts,
          )
      },
    )(
      "SV rejects as it is out of the range",
      _ => {
        loggerFactory.assertLogs(
          runTrigger,
          _.warningMessage should include(
            "0.0010000000 which is outside of the configured accepted range RangeConfig(0.01,100.0), clamping to 0.01"
          ),
        )
        BigDecimal(
          sv1Backend.listAmuletPriceVotes().loneElement.payload.amuletPrice.toScala.value
        ) shouldBe BigDecimal(0.01)
      },
    )
  }

}
