package com.daml.network.integration.tests

import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import com.daml.network.codegen.java.splice.amuletrules.AmuletRules_AddFutureAmuletConfigSchedule
import com.daml.network.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import com.daml.network.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_AddFutureAmuletConfigSchedule
import com.daml.network.codegen.java.splice.globaldomain.{
  AmuletGlobalDomainConfig,
  BaseRateTrafficLimits,
  DomainFeesConfig,
}
import com.daml.network.util.AmuletConfigSchedule

import java.time.Instant
import java.time.temporal.ChronoUnit

class SvReconcileDomainConfigIntegrationTest extends SvIntegrationTestBase {

  "SV automation reconcile amulet config change to domain parameter" in { implicit env =>
    initDso()

    val globalDomainId = inside(sv1Backend.participantClient.domains.list_connected()) {
      case Seq(domain) =>
        domain.domainId
    }

    val amuletConfig: AmuletConfig[USD] =
      sv1ScanBackend.getAmuletRules().payload.configSchedule.initialValue

    clue("domain parameter is initialized") {
      eventually() {
        val trafficControlParameters =
          sv1Backend.participantClientWithAdminToken.topology.domain_parameters
            .get_dynamic_domain_parameters(globalDomainId)
            .trafficControlParameters
            .value
        trafficControlParameters.maxBaseTrafficAmount.value shouldBe
          amuletConfig.globalDomain.fees.baseRateTrafficLimits.burstAmount
        trafficControlParameters.maxBaseTrafficAccumulationDuration.underlying.toMicros shouldBe
          amuletConfig.globalDomain.fees.baseRateTrafficLimits.burstWindow.microseconds
        trafficControlParameters.readVsWriteScalingFactor.value.toLong shouldBe
          amuletConfig.globalDomain.fees.readVsWriteScalingFactor
      }
    }

    val newAmuletConfig = createAmuletConfig(
      amuletConfig,
      amuletConfig.globalDomain.fees.baseRateTrafficLimits.burstAmount + 1,
      new RelTime(
        amuletConfig.globalDomain.fees.baseRateTrafficLimits.burstWindow.microseconds + 1
      ),
      amuletConfig.globalDomain.fees.readVsWriteScalingFactor + 1,
    )
    val configChangeAction = new ARC_AmuletRules(
      new CRARC_AddFutureAmuletConfigSchedule(
        new AmuletRules_AddFutureAmuletConfigSchedule(
          new com.daml.network.codegen.java.da.types.Tuple2(
            Instant.now().plus(20, ChronoUnit.SECONDS),
            newAmuletConfig,
          )
        )
      )
    )
    actAndCheck(
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
            )
          },
        )("vote request has been created", _ => sv1Backend.listVoteRequests().loneElement)

        clue(s"sv2-4 accept") {
          Seq(sv2Backend, sv3Backend, sv4Backend).map(sv =>
            eventuallySucceeds() {
              sv.castVote(
                voteRequest.contractId,
                true,
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
        val config = AmuletConfigSchedule(newAmuletRules).getConfigAsOf(now).globalDomain.fees
        config.baseRateTrafficLimits.burstAmount shouldBe
          amuletConfig.globalDomain.fees.baseRateTrafficLimits.burstAmount + 1
        config.baseRateTrafficLimits.burstWindow shouldBe new RelTime(
          amuletConfig.globalDomain.fees.baseRateTrafficLimits.burstWindow.microseconds + 1
        )
        config.readVsWriteScalingFactor shouldBe amuletConfig.globalDomain.fees.readVsWriteScalingFactor + 1
      },
    )

    clue("domain parameter is reconciled") {
      eventually() {
        val trafficControlParameters =
          sv1Backend.participantClientWithAdminToken.topology.domain_parameters
            .get_dynamic_domain_parameters(globalDomainId)
            .trafficControlParameters
            .value
        trafficControlParameters.maxBaseTrafficAmount.value shouldBe
          amuletConfig.globalDomain.fees.baseRateTrafficLimits.burstAmount + 1
        trafficControlParameters.maxBaseTrafficAccumulationDuration.underlying.toMicros shouldBe
          amuletConfig.globalDomain.fees.baseRateTrafficLimits.burstWindow.microseconds + 1
        trafficControlParameters.readVsWriteScalingFactor.value.toLong shouldBe
          amuletConfig.globalDomain.fees.readVsWriteScalingFactor + 1
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
    new AmuletGlobalDomainConfig(
      amuletConfig.globalDomain.requiredDomains,
      amuletConfig.globalDomain.activeDomain,
      new DomainFeesConfig(
        new BaseRateTrafficLimits(
          burstAmount,
          burstWindow,
        ),
        amuletConfig.globalDomain.fees.extraTrafficPrice,
        readVsWriteScalingFactor,
        amuletConfig.globalDomain.fees.minTopupAmount,
      ),
    ),
    amuletConfig.tickDuration,
    amuletConfig.packageConfig,
  )

}
