package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_SetConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.{
  AmuletDecentralizedSynchronizerConfig,
  BaseRateTrafficLimits,
  SynchronizerFeesConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_SetConfig
import org.lfdecentralizedtrust.splice.util.AmuletConfigSchedule

import scala.concurrent.duration.*

class SvReconcileSynchronizerConfigIntegrationTest extends SvIntegrationTestBase {

  "SV automation reconcile amulet config change to domain parameter" in { implicit env =>
    initDso()

    val amuletConfig: AmuletConfig[USD] =
      sv1ScanBackend.getAmuletRules().payload.configSchedule.initialValue

    clue("domain parameter is initialized") {
      eventually() {
        val trafficControlParameters =
          sv1Backend.participantClientWithAdminToken.topology.synchronizer_parameters
            .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)
            .trafficControl
            .value
        trafficControlParameters.maxBaseTrafficAmount.value shouldBe
          amuletConfig.decentralizedSynchronizer.fees.baseRateTrafficLimits.burstAmount
        trafficControlParameters.maxBaseTrafficAccumulationDuration.underlying.toMicros shouldBe
          amuletConfig.decentralizedSynchronizer.fees.baseRateTrafficLimits.burstWindow.microseconds
        trafficControlParameters.readVsWriteScalingFactor.value.toLong shouldBe
          amuletConfig.decentralizedSynchronizer.fees.readVsWriteScalingFactor
      }
    }

    val newAmuletConfig = createAmuletConfig(
      amuletConfig,
      amuletConfig.decentralizedSynchronizer.fees.baseRateTrafficLimits.burstAmount + 1,
      new RelTime(
        amuletConfig.decentralizedSynchronizer.fees.baseRateTrafficLimits.burstWindow.microseconds + 1000_000
      ),
      amuletConfig.decentralizedSynchronizer.fees.readVsWriteScalingFactor + 1,
    )
    val configChangeAction = new ARC_AmuletRules(
      new CRARC_SetConfig(
        new AmuletRules_SetConfig(
          newAmuletConfig,
          amuletConfig,
        )
      )
    )
    actAndCheck(timeUntilSuccess = 30.seconds)(
      "Voting on a AmuletRules config change for new fees config", {
        val (_, voteRequest) = actAndCheck(
          "Creating vote request",
          eventuallySucceeds() {
            sv1Backend.createVoteRequest(
              sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
              configChangeAction,
              "url",
              "description",
              sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
              None,
            )
          },
        )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)

        clue(s"sv2 and sv3 accept") {
          Seq(sv2Backend, sv3Backend).map(sv =>
            eventuallySucceeds() {
              sv.castVote(
                voteRequest.contractId,
                isAccepted = true,
                "url",
                "description",
              )
            }
          )
        }
      },
    )(
      "observing AmuletRules with new config",
      _ => {
        val newAmuletRules = sv1Backend.getDsoInfo().amuletRules
        val now = env.environment.clock.now
        val config =
          AmuletConfigSchedule(newAmuletRules).getConfigAsOf(now).decentralizedSynchronizer.fees
        config.baseRateTrafficLimits.burstAmount shouldBe
          amuletConfig.decentralizedSynchronizer.fees.baseRateTrafficLimits.burstAmount + 1
        config.baseRateTrafficLimits.burstWindow shouldBe new RelTime(
          amuletConfig.decentralizedSynchronizer.fees.baseRateTrafficLimits.burstWindow.microseconds + 1000_000
        )
        config.readVsWriteScalingFactor shouldBe amuletConfig.decentralizedSynchronizer.fees.readVsWriteScalingFactor + 1
      },
    )

    clue("domain parameter is reconciled") {
      eventually() {
        val trafficControlParameters =
          sv1Backend.participantClientWithAdminToken.topology.synchronizer_parameters
            .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)
            .trafficControl
            .value
        trafficControlParameters.maxBaseTrafficAmount.value shouldBe
          amuletConfig.decentralizedSynchronizer.fees.baseRateTrafficLimits.burstAmount + 1
        trafficControlParameters.maxBaseTrafficAccumulationDuration.underlying.toMicros shouldBe
          amuletConfig.decentralizedSynchronizer.fees.baseRateTrafficLimits.burstWindow.microseconds + 1000_000
        trafficControlParameters.readVsWriteScalingFactor.value.toLong shouldBe
          amuletConfig.decentralizedSynchronizer.fees.readVsWriteScalingFactor + 1
      }
    }
  }

  private def createAmuletConfig(
      amuletConfig: AmuletConfig[USD],
      burstAmount: Long,
      burstWindow: RelTime,
      readVsWriteScalingFactor: Long,
  ) = new AmuletConfig(
    amuletConfig.transferConfig,
    amuletConfig.issuanceCurve,
    new AmuletDecentralizedSynchronizerConfig(
      amuletConfig.decentralizedSynchronizer.requiredSynchronizers,
      amuletConfig.decentralizedSynchronizer.activeSynchronizer,
      new SynchronizerFeesConfig(
        new BaseRateTrafficLimits(
          burstAmount,
          burstWindow,
        ),
        amuletConfig.decentralizedSynchronizer.fees.extraTrafficPrice,
        readVsWriteScalingFactor,
        amuletConfig.decentralizedSynchronizer.fees.minTopupAmount,
      ),
    ),
    amuletConfig.tickDuration,
    amuletConfig.packageConfig,
    amuletConfig.transferPreapprovalFee,
    amuletConfig.featuredAppActivityMarkerAmount,
    amuletConfig.optDevelopmentFundManager,
    amuletConfig.externalPartyConfigStateTickDuration,
  )

}
