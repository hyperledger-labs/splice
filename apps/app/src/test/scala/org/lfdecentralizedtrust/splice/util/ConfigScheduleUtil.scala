package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  AmuletRules_AddFutureAmuletConfigSchedule,
  AmuletRules_RemoveFutureAmuletConfigSchedule,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ActionRequiringConfirmation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.{
  CRARC_AddFutureAmuletConfigSchedule,
  CRARC_RemoveFutureAmuletConfigSchedule,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.schedule.Schedule
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.console.SvAppBackendReference
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  SpliceTestConsoleEnvironment,
  TestCommon,
}

import java.time.{Duration, Instant}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*

//TODO(#16139): remove this utility
trait ConfigScheduleUtil extends TestCommon {

  /** Helper function to create AmuletConfig's in tests for amulet config changes. Uses the `currentSchedule` as a reference
    * to fill in the id of the activeSynchronizer.
    */
  protected def mkUpdatedAmuletConfig(
      amuletRules: Contract[AmuletRules.ContractId, AmuletRules],
      tickDuration: NonNegativeFiniteDuration,
      maxNumInputs: Int = 100,
      holdingFee: BigDecimal = SpliceUtil.defaultHoldingFee.rate,
  )(implicit
      env: SpliceTests.SpliceTestConsoleEnvironment
  ): splice.amuletconfig.AmuletConfig[splice.amuletconfig.USD] = {
    val existingAmuletConfig = AmuletConfigSchedule(amuletRules)
      .getConfigAsOf(env.environment.clock.now)
    val existingTransferConfig = existingAmuletConfig.transferConfig
    new splice.amuletconfig.AmuletConfig(
      new splice.amuletconfig.TransferConfig(
        existingTransferConfig.createFee,
        new splice.fees.RatePerRound(
          holdingFee.bigDecimal.setScale(10, BigDecimal.RoundingMode.HALF_EVEN).bigDecimal
        ),
        existingTransferConfig.transferFee,
        existingTransferConfig.lockHolderFee,
        existingTransferConfig.extraFeaturedAppRewardAmount,
        maxNumInputs,
        existingTransferConfig.maxNumOutputs,
        existingTransferConfig.maxNumLockHolders,
      ),
      existingAmuletConfig.issuanceCurve,
      existingAmuletConfig.decentralizedSynchronizer,
      new RelTime(TimeUnit.NANOSECONDS.toMicros(tickDuration.duration.toNanos)),
      existingAmuletConfig.packageConfig,
      existingAmuletConfig.transferPreapprovalFee,
      existingAmuletConfig.featuredAppActivityMarkerAmount,
    )
  }

  /** Create a new config schedule reusing the active domain value from the existing one.
    * Intended for testing only.
    */
  def createConfigSchedule(
      amuletRules: Contract[AmuletRules.ContractId, AmuletRules],
      newSchedules: (Duration, splice.amuletconfig.AmuletConfig[splice.amuletconfig.USD])*
  )(implicit env: SpliceTestConsoleEnvironment): Schedule[Instant, AmuletConfig[USD]] = {
    val configSchedule = {
      new splice.schedule.Schedule(
        mkUpdatedAmuletConfig(amuletRules, defaultTickDuration),
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
            None,
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
