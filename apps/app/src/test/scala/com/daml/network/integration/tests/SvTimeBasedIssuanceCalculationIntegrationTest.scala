package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.types.Round
import com.daml.network.codegen.java.cc.coin.{AppRewardCoupon, ValidatorRewardCoupon}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import scala.jdk.CollectionConverters.*

/** Dedicated test suite to do command submissions with actAs=svc
  */
class SvTimeBasedIssuanceCalculationIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithSharedEnvironment {

  override def environmentDefinition: CNNodeEnvironmentDefinition =
    super.environmentDefinition.withManualStart

  "calculation of issuance per coin" in { implicit env =>
    startAllSync(sv1Backend, sv1ScanBackend)
    val latestIssueRound = clue("Ensure there is at least one issuing round") {
      eventually() {
        getSortedIssuingRounds(
          sv1Backend.participantClientWithAdminToken,
          svcParty,
        ).lastOption.getOrElse {
          logger.info("No issuing round found -- advancing rounds by one tick")
          advanceRoundsByOneTick
          fail("No issuing round found")
        }
      }
    }
    val currentRoundNum = latestIssueRound.data.round.number.toLong + 1L

    // 3 unfeatured app rewards & 3 featured app rewards & 3 validator rewards, 2 of each for round 0 and one for round 1
    // to check we sum up but only for the right round.
    val rewards = Seq(
      // featured app rewards for a total of 200.0 in round 0
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        true,
        BigDecimal(1.0).bigDecimal,
        new Round(currentRoundNum),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        true,
        BigDecimal(199.0).bigDecimal,
        new Round(currentRoundNum),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        true,
        BigDecimal(3.0).bigDecimal,
        new Round(currentRoundNum + 1L),
      ),
      // unfeatured app rewards for a total of 9800.0 in round 0
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        false,
        BigDecimal(2.5).bigDecimal,
        new Round(currentRoundNum),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        false,
        BigDecimal(9797.5).bigDecimal,
        new Round(currentRoundNum),
      ),
      new AppRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        false,
        BigDecimal(5.0).bigDecimal,
        new Round(currentRoundNum + 1L),
      ),
      // validator rewards for a total of 10000.0 in round 0
      new ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(3.0).bigDecimal,
        new Round(currentRoundNum),
      ),
      new ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(9997.0).bigDecimal,
        new Round(currentRoundNum),
      ),
      new ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(15.0).bigDecimal,
        new Round(currentRoundNum + 1L),
      ),
    )
    // Create a bunch of rewards directly
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
      actAs = Seq(svcParty),
      optTimeout = None,
      commands = rewards.flatMap(_.create.commands.asScala.toSeq),
    )

    // Start sv2-4 after we did the command submissions as the SVC party
    startAllSync(sv2Backend, sv3Backend, sv4Backend)

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        advanceRoundsByOneTick
        eventually() {
          val latestIssueRoundAfter1Tick = getSortedIssuingRounds(
            sv1Backend.participantClientWithAdminToken,
            svcParty,
          ).lastOption.value
          latestIssueRoundAfter1Tick.data.round.number shouldBe currentRoundNum
        }
      },
      entries =>
        // we should expect logs of creating confirmation by at least 3 SV which is the required number of confirmations.
        forAtLeast(3, entries)(
          _.message should include(
            // TODO(#8819): also test for validator faucet coupons once they are summarized as well
            // TODO(#9173): also test for SV reward coupons once they are summarized as well
            s"created confirmation for summarizing mining round with com.daml.network.codegen.java.cc.issuance.OpenMiningRoundSummary(10000.0000000000, 200.0000000000, 9800.0000000000, 0, Optional.empty)"
          )
        ),
    )

    def decimal(d: Double): java.math.BigDecimal = BigDecimal(d).setScale(10).bigDecimal

    val issuingRound =
      getSortedIssuingRounds(sv1Backend.participantClientWithAdminToken, svcParty).lastOption.value

    issuingRound.data.issuancePerValidatorRewardCoupon shouldBe decimal(0.2000000000)
    issuingRound.data.issuancePerFeaturedAppRewardCoupon shouldBe decimal(100.0000000000)
    issuingRound.data.issuancePerUnfeaturedAppRewardCoupon shouldBe decimal(0.6000000000)
  }

}
