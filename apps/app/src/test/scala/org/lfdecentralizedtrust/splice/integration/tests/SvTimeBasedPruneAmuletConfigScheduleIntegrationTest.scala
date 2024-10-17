package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_AddFutureAmuletConfigSchedule
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_AddFutureAmuletConfigSchedule
import org.lfdecentralizedtrust.splice.codegen.java.splice.schedule.Schedule
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.WalletTestUtil

import scala.jdk.CollectionConverters.*

class SvTimeBasedPruneAmuletConfigScheduleIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with SvTimeBasedIntegrationTestUtil
    with WalletTestUtil
    with WalletTxLogTestUtil {

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)

  "dso delegate" should {
    "prune amulet config" in { implicit env =>
      val amuletConfig: AmuletConfig[USD] =
        sv1ScanBackend.getAmuletRules().payload.configSchedule.initialValue
      val newAmuletConfig =
        new AmuletConfig(
          amuletConfig.transferConfig,
          amuletConfig.issuanceCurve,
          amuletConfig.decentralizedSynchronizer,
          new RelTime(java.time.Duration.ofMinutes(1).toMillis * 1000L),
          amuletConfig.packageConfig,
        )
      val scheduledTime = getLedgerTime.plus(java.time.Duration.ofMinutes(1)).toInstant
      val configChangeAction = new ARC_AmuletRules(
        new CRARC_AddFutureAmuletConfigSchedule(
          new AmuletRules_AddFutureAmuletConfigSchedule(
            new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
              scheduledTime,
              newAmuletConfig,
            )
          )
        )
      )
      actAndCheck(
        "Create a vote request for adding a new scheduled config",
        sv1Backend.createVoteRequest(
          sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
          configChangeAction,
          "url",
          "description",
          sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
        ),
      )(
        "schedule is updated",
        _ => {
          val schedule =
            sv1ScanBackend.getAmuletRules().payload.configSchedule
          schedule.futureValues should not be empty
          schedule shouldBe new Schedule(
            amuletConfig,
            Seq(new Tuple2(scheduledTime, newAmuletConfig)).asJava,
          )
        },
      )
      actAndCheck("Advance time past scheduled time", advanceTime(java.time.Duration.ofMinutes(2)))(
        "config gets pruned",
        _ => {
          val schedule =
            sv1ScanBackend.getAmuletRules().payload.configSchedule
          schedule shouldBe new Schedule(
            newAmuletConfig,
            Seq.empty[Tuple2[java.time.Instant, AmuletConfig[USD]]].asJava,
          )
        },
      )
    }
  }

}
