package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.PositiveDurationSeconds
import org.lfdecentralizedtrust.splice.config.PruningConfig
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.TriggerTestUtil

import scala.concurrent.duration.DurationInt

class ParticipantPruningIntegrationTest extends IntegrationTest with TriggerTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransform((_, conf) =>
        conf.copy(validatorApps =
          conf.validatorApps.updatedWith(InstanceName.tryCreate("sv1Validator")) {
            _.map { config =>
              config.copy(
                participantPruningSchedule = Some(
                  PruningConfig(
                    "0 /1 * * * ?",
                    PositiveDurationSeconds.tryFromDuration(1.seconds),
                    PositiveDurationSeconds.tryFromDuration(1.seconds),
                  )
                )
              )
            }
          }
        )
      )
      .withManualStart
  }

  "Participants" should {

    "prune" in { implicit env =>
      clue("Start sv1") {
        initDsoWithSv1Only()
      }

      println(sv1ValidatorBackend.participantClient.pruning.get_schedule())

//      sv1ValidatorBackend.participantClient.pruning.get_schedule() shouldBe PruningConfig(
//        "0 /10 * * * ?",
//        PositiveDurationSeconds.tryFromDuration(1.seconds),
//        PositiveDurationSeconds.tryFromDuration(1.seconds),
//      )

//      clue("Check sv1 participant is being pruned") {
//        eventually(70.seconds) {
//          loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.INFO))(
//            {},
//            lines =>
//              forAtLeast(1, lines) {
//                _.message should contain("Pruning lapi")
//              },
//          )
//        }
//      }
    }
  }
}
