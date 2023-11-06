package com.daml.network.util

import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.coinrules.{
  CoinRules_AddFutureCoinConfigSchedule,
  CoinRules_RemoveFutureCoinConfigSchedule,
}
import com.daml.network.codegen.java.cc.coinconfig.{CoinConfig, USD}
import com.daml.network.codegen.java.cc.schedule.Schedule
import com.daml.network.codegen.java.cn.svcrules.ActionRequiringConfirmation
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_CoinRules
import com.daml.network.codegen.java.cn.svcrules.coinrules_actionrequiringconfirmation.{
  CRARC_AddFutureCoinConfigSchedule,
  CRARC_RemoveFutureCoinConfigSchedule,
}
import com.daml.network.codegen.java.da.types.Tuple2
import com.daml.network.config.CNThresholds
import com.daml.network.console.SvAppBackendReference
import com.daml.network.integration.tests.CNNodeTests
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.CNNodeUtil.defaultCoinConfig
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.DomainId

import java.time.{Duration, Instant}
import scala.jdk.CollectionConverters.*

trait ConfigScheduleUtil extends CNNodeTestCommon {

  /** Helper function to create CoinConfig's in tests for coin config changes. Uses the `currentSchedule` as a reference
    * to fill in the id of the activeDomain.
    */
  protected def mkUpdatedCoinConfig(
      currentSchedule: Schedule[Instant, CoinConfig[USD]],
      tickDuration: NonNegativeFiniteDuration,
      maxNumInputs: Int = 100,
      holdingFee: BigDecimal = CNNodeUtil.defaultHoldingFee.rate,
      nextDomainId: Option[DomainId] = None,
  )(implicit
      env: CNNodeTests.CNNodeTestConsoleEnvironment
  ): cc.coinconfig.CoinConfig[cc.coinconfig.USD] = {
    val activeDomainId =
      CoinConfigSchedule(currentSchedule)
        .getConfigAsOf(env.environment.clock.now)
        .globalDomain
        .activeDomain
    val trafficControlConfig = defaultTrafficControlConfig
    defaultCoinConfig(
      tickDuration,
      maxNumInputs,
      DomainId.tryFromString(activeDomainId),
      trafficControlConfig.baseRateBurstAmount.value,
      trafficControlConfig.baseRateBurstWindow,
      trafficControlConfig.readVsWriteScalingFactor.value,
      holdingFee,
      nextDomainId = nextDomainId,
    )
  }

  /** Create a new config schedule reusing the active domain value from the existing one.
    * Intended for testing only.
    */
  def createConfigSchedule(
      currentSchedule: Schedule[Instant, CoinConfig[USD]],
      newSchedules: (Duration, cc.coinconfig.CoinConfig[cc.coinconfig.USD])*
  )(implicit env: CNNodeTestConsoleEnvironment): Schedule[Instant, CoinConfig[USD]] = {
    val configSchedule = {
      new cc.schedule.Schedule(
        mkUpdatedCoinConfig(currentSchedule, defaultTickDuration),
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

  def setFutureConfigSchedule(configSchedule: Schedule[Instant, CoinConfig[USD]])(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    // clean all futureValues
    sv1Backend
      .getSvcInfo()
      .coinRules
      .payload
      .configSchedule
      .futureValues
      .forEach(value => {
        votingFlow(
          new ARC_CoinRules(
            new CRARC_RemoveFutureCoinConfigSchedule(
              new CoinRules_RemoveFutureCoinConfigSchedule(value._1)
            )
          )
        )
      })
    // add new futureValues
    configSchedule.futureValues.forEach(value => {
      votingFlow(
        new ARC_CoinRules(
          new CRARC_AddFutureCoinConfigSchedule(
            new CoinRules_AddFutureCoinConfigSchedule(value)
          )
        )
      )
    })
  }

  def votingFlow(action: ActionRequiringConfirmation)(implicit
      env: CNNodeTestConsoleEnvironment
  ): Unit = {
    val svcRules = sv1Backend.getSvcInfo().svcRules
    val sv1Party = sv1Backend.getSvcInfo().svParty

    val voteRequestCid = clue("request vote for config schedule change") {
      val (_, voteRequestCid) = actAndCheck(
        "sv1 creates a vote request", {
          sv1Backend.createVoteRequest(
            sv1Party.toProtoPrimitive,
            action,
            "url",
            "description",
            sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
          )
        },
      )(
        "The vote request has been created and sv1 accepts",
        _ => {
          sv1Backend.listVoteRequests() should not be empty
          val head = sv1Backend.listVoteRequests().head.contractId
          sv1Backend.listVotes(Vector(head.contractId)) should have size 1
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
                sv.is_running && sv.name != "sv1" && voteCount < CNThresholds
                  .requiredNumVotes(svcRules)
              ) {
                eventually() {
                  sv.listVoteRequests()
                    .filter(
                      _.contractId.contractId == voteRequestCid.contractId
                    ) should have size 1
                }
                actAndCheck(
                  s"${sv.name} casts a vote", {
                    sv.castVote(
                      voteRequestCid,
                      true,
                      "url",
                      "description",
                    )
                    voteCount += 1
                  },
                )(
                  s"the ${sv.name} vote has been cast",
                  _ => {
                    sv.listVotes(Vector(voteRequestCid.contractId)) should have size voteCount
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
