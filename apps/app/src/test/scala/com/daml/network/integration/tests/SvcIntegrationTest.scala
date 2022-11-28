package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cc.coin.*
import com.daml.network.codegen.java.cc.round.SummarizingMiningRound
import com.daml.network.integration.tests.CoinTests.CoinIntegrationTest
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import scala.jdk.CollectionConverters.*

class SvcIntegrationTest extends CoinIntegrationTest {

  "restart cleanly" in { implicit env =>
    // TODO(M1-92): share tests for common properties of CoinApps, like restartabilty
    svc.stop()
    svc.startSync()
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
    svc.remoteParticipant.ledger_api.commands.submitJava(
      actAs = Seq(svcParty),
      optTimeout = None,
      commands = rewards.flatMap(_.create.commands.asScala.toSeq),
    )

    loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
      {
        svc.startSummarizingRound(0)
        eventually() {
          svc.remoteParticipant.ledger_api.acs
            .filterJava(SummarizingMiningRound.COMPANION)(svcParty) shouldBe empty
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
