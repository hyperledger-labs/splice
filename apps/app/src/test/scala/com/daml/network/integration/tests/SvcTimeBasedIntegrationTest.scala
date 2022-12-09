package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cc.coin.*
import com.daml.network.codegen.java.cc.round.*
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.daml.network.util.{TimeTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class SvcTimeBasedIntegrationTest
    extends CoinIntegrationTest
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)

  "round management" in { implicit env =>
    // Sync with background automation that onboards validator.
    eventually()({
      val rounds = svc.remoteParticipantWithAdminToken.ledger_api.acs
        .filterJava(cc.round.OpenMiningRound.COMPANION)(svcParty)
      rounds.map {
        _.data.observers should have length 4
      }
      rounds should have size 3
    })

    // one tick - round 0 closes.
    advanceRoundsByOneTick
    eventually()(
      svc.remoteParticipantWithAdminToken.ledger_api.acs
        .filterJava(IssuingMiningRound.COMPANION)(svcParty) should have size 1
    )
    // next tick - round 1 closes.
    advanceRoundsByOneTick
    eventually()(
      svc.remoteParticipantWithAdminToken.ledger_api.acs
        .filterJava(IssuingMiningRound.COMPANION)(svcParty) should have size 2
    )
    // next tick - round 2 closes.
    advanceRoundsByOneTick
    eventually()(
      svc.remoteParticipantWithAdminToken.ledger_api.acs
        .filterJava(IssuingMiningRound.COMPANION)(svcParty) should have size 3
    )

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        // next tick - issuing round 0 can be closed
        // not using `advanceRoundsByOneTick` because this interferes with checking the state of the ClosedMiningRounds
        advanceTime(java.time.Duration.ofSeconds(160))
        // poll frequently, so we see the closed mining round before the automation archives it.
        eventually(maxPollInterval = 100.milliseconds)(
          svc.remoteParticipantWithAdminToken.ledger_api.acs
            .filterJava(ClosedMiningRound.COMPANION)(svcParty) should have size 1
        )
        eventually()( // .. hence even though a fourth issuing round is created, we end up with 3 active issuing rounds eventually.
          svc.remoteParticipantWithAdminToken.ledger_api.acs
            .filterJava(IssuingMiningRound.COMPANION)(svcParty) should have size 3
        )
        eventually()( // closed round is archived eventually.
          svc.remoteParticipantWithAdminToken.ledger_api.acs
            .filterJava(ClosedMiningRound.COMPANION)(svcParty) should have size 0
        )
      },
      entries => {
        forAtLeast(1, entries)(
          _.message should include(
            "successfully created the closed mining round with cid"
          )
        )
        forAtLeast(1, entries)(
          _.message should include(
            "successfully archived closed mining round"
          )
        )
      },
    )

  }

  "total burn calculation" in { implicit env =>
    // 3 app rewards & 3 validator rewards, 2 of each for round 0 and one for round 1
    // to check we sum up but only for the right round.
    val rewards = Seq(
      new AppReward(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(1.0).bigDecimal,
        new Round(0),
      ),
      new AppReward(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(2.0).bigDecimal,
        new Round(0),
      ),
      new AppReward(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(5.0).bigDecimal,
        new Round(1),
      ),
      new ValidatorReward(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(3.0).bigDecimal,
        new Round(0),
      ),
      new ValidatorReward(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(4.0).bigDecimal,
        new Round(0),
      ),
      new ValidatorReward(
        svcParty.toProtoPrimitive,
        svcParty.toProtoPrimitive,
        BigDecimal(15.0).bigDecimal,
        new Round(1),
      ),
    )
    // Create a bunch of rewards directly
    svc.remoteParticipantWithAdminToken.ledger_api.commands.submitJava(
      actAs = Seq(svcParty),
      optTimeout = None,
      commands = rewards.flatMap(_.create.commands.asScala.toSeq),
    )

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        advanceRoundsByOneTick
        eventually() {
          svc.remoteParticipantWithAdminToken.ledger_api.acs
            .filterJava(IssuingMiningRound.COMPANION)(svcParty) should have size 1
        }
      },
      entries =>
        forAtLeast(1, entries)(
          _.message should include(
            s"successfully archived summarizing mining round with burn ${1 + 2 + 3 + 4}"
          )
        ),
    )
  }

}
