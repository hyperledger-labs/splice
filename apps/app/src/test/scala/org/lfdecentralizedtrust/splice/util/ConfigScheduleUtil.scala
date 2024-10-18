package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules_AddFutureAmuletConfigSchedule,
  AmuletRules_RemoveFutureAmuletConfigSchedule,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.schedule.Schedule
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ActionRequiringConfirmation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.{
  CRARC_AddFutureAmuletConfigSchedule,
  CRARC_RemoveFutureAmuletConfigSchedule,
}
import org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.console.SvAppBackendReference
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  TestCommon,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.SpliceUtil.defaultAmuletConfig
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.DomainId

import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters.*

trait ConfigScheduleUtil extends TestCommon {

  /** Helper function to create AmuletConfig's in tests for amulet config changes. Uses the `currentSchedule` as a reference
    * to fill in the id of the activeSynchronizer.
    */
  protected def mkUpdatedAmuletConfig(
      currentSchedule: Schedule[Instant, AmuletConfig[USD]],
      tickDuration: NonNegativeFiniteDuration,
      maxNumInputs: Int = 100,
      holdingFee: BigDecimal = SpliceUtil.defaultHoldingFee.rate,
      nextDomainId: Option[DomainId] = None,
  )(implicit
      env: SpliceTests.SpliceTestConsoleEnvironment
  ): splice.amuletconfig.AmuletConfig[splice.amuletconfig.USD] = {
    val activeSynchronizerId =
      AmuletConfigSchedule(currentSchedule)
        .getConfigAsOf(env.environment.clock.now)
        .decentralizedSynchronizer
        .activeSynchronizer
    val domainFeesConfig = defaultSynchronizerFeesConfig
    defaultAmuletConfig(
      tickDuration,
      maxNumInputs,
      DomainId.tryFromString(activeSynchronizerId),
      domainFeesConfig.extraTrafficPrice.value,
      domainFeesConfig.minTopupAmount.value,
      domainFeesConfig.baseRateBurstAmount.value,
      domainFeesConfig.baseRateBurstWindow,
      domainFeesConfig.readVsWriteScalingFactor.value,
      holdingFee = holdingFee,
      nextDomainId = nextDomainId,
    )
  }

  /** Create a new config schedule reusing the active domain value from the existing one.
    * Intended for testing only.
    */
  def createConfigSchedule(
      currentSchedule: Schedule[Instant, AmuletConfig[USD]],
      newSchedules: (Duration, splice.amuletconfig.AmuletConfig[splice.amuletconfig.USD])*
  )(implicit env: SpliceTestConsoleEnvironment): Schedule[Instant, AmuletConfig[USD]] = {
    val configSchedule = {
      new splice.schedule.Schedule(
        mkUpdatedAmuletConfig(currentSchedule, defaultTickDuration),
        newSchedules
          .map { case (durationUntilScheduled, config) =>
            new Tuple2(
              env.environment.clock.now.add(durationUntilScheduled).toInstant,
              config,
            )
          }
          .toList
          .asJava,
      )
    }
    configSchedule
  }

  def setFutureConfigSchedule(configSchedule: Schedule[Instant, AmuletConfig[USD]])(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    // clean all futureValues
    sv1Backend
      .getDsoInfo()
      .amuletRules
      .payload
      .configSchedule
      .futureValues
      .forEach(value => {
        votingFlow(
          new ARC_AmuletRules(
            new CRARC_RemoveFutureAmuletConfigSchedule(
              new AmuletRules_RemoveFutureAmuletConfigSchedule(value._1)
            )
          )
        )
      })
    // add new futureValues
    configSchedule.futureValues.forEach(value => {
      votingFlow(
        new ARC_AmuletRules(
          new CRARC_AddFutureAmuletConfigSchedule(
            new AmuletRules_AddFutureAmuletConfigSchedule(value)
          )
        )
      )
    })
  }

  def votingFlow(action: ActionRequiringConfirmation)(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    val dsoRules = sv1Backend.getDsoInfo().dsoRules
    val sv1Party = sv1Backend.getDsoInfo().svParty

    val voteRequestCid = clue("request vote for config schedule change") {
      val (_, voteRequestCid) = actAndCheck(
        "sv1 creates a vote request", {
          sv1Backend.createVoteRequest(
            sv1Party.toProtoPrimitive,
            action,
            "url",
            "description",
            sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
          )
        },
      )(
        "The vote request has been created and sv1 accepts",
        _ => {
          sv1Backend.listVoteRequests() should not be empty
          val head = sv1Backend.listVoteRequests().loneElement
          sv1Backend.lookupVoteRequest(head.contractId).payload.votes should have size 1
          head
        },
      )
      voteRequestCid
    }
    clue("cast votes for config schedule change") {
      var voteCount: Long = 1
      env.svs.all
        .foreach(sv => {
          sv match {
            case sv: SvAppBackendReference =>
              if (
                sv.is_running && sv.name != "sv1" && voteCount < Thresholds
                  .requiredNumVotes(dsoRules)
              ) {
                eventually() {
                  sv.listVoteRequests()
                    .filter(
                      _.payload.trackingCid == voteRequestCid.contractId
                    ) should have size 1
                }
                actAndCheck(
                  s"${sv.name} casts a vote", {
                    sv.castVote(
                      voteRequestCid.contractId,
                      true,
                      "url",
                      "description",
                    )
                    voteCount += 1
                  },
                )(
                  s"the ${sv.name} vote has been cast",
                  _ => {
                    sv.lookupVoteRequest(voteRequestCid.contractId)
                      .payload
                      .votes should have size voteCount
                    sv.listVoteRequests() shouldBe empty
                  },
                )
              }
            case _ =>
          }
        })
    }
  }

}
