package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.SynchronizerId
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.{AmuletConfig, USD}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  AmuletRules,
  AmuletRules_SetConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_SetConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRules,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  SpliceTestConsoleEnvironment,
  TestCommon,
}
import org.lfdecentralizedtrust.splice.util.SpliceUtil.defaultAmuletConfig

import java.time.Duration
import java.util.UUID

trait AmuletConfigUtil extends TestCommon {

  /** Helper function to create AmuletConfig's in tests for amulet config changes. Uses the `currentSchedule` as a reference
    * to fill in the id of the activeSynchronizer.
    */
  protected def mkUpdatedAmuletConfig(
      amuletRules: Contract[AmuletRules.ContractId, AmuletRules],
      tickDuration: NonNegativeFiniteDuration,
      maxNumInputs: Int = 100,
      holdingFee: BigDecimal = SpliceUtil.defaultHoldingFee.rate,
      nextSynchronizerId: Option[SynchronizerId] = None,
  )(implicit
      env: SpliceTests.SpliceTestConsoleEnvironment
  ): splice.amuletconfig.AmuletConfig[splice.amuletconfig.USD] = {
    val activeSynchronizerId =
      AmuletConfigSchedule(amuletRules)
        .getConfigAsOf(env.environment.clock.now)
        .decentralizedSynchronizer
        .activeSynchronizer
    val domainFeesConfig = defaultSynchronizerFeesConfig
    defaultAmuletConfig(
      tickDuration,
      maxNumInputs,
      SynchronizerId.tryFromString(activeSynchronizerId),
      domainFeesConfig.extraTrafficPrice.value,
      domainFeesConfig.minTopupAmount.value,
      domainFeesConfig.baseRateBurstAmount.value,
      domainFeesConfig.baseRateBurstWindow,
      domainFeesConfig.readVsWriteScalingFactor.value,
      holdingFee = holdingFee,
      nextSynchronizerId = nextSynchronizerId,
    )
  }

  def setAmuletConfig(
      configs: Seq[(Option[Duration], AmuletConfig[USD], AmuletConfig[USD])],
      expiration: Duration = Duration.ofSeconds(60),
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    // add new configs
    configs.foreach { case (duration, newConfig, baseConfig) =>
      votingFlow(
        new ARC_AmuletRules(
          new CRARC_SetConfig(
            new AmuletRules_SetConfig(
              newConfig,
              baseConfig,
            )
          )
        ),
        duration,
        accept = true,
        expiration,
      )
    }
  }

  def votingFlow(
      action: ActionRequiringConfirmation,
      effectivity: Option[Duration],
      accept: Boolean,
      expiration: Duration,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    val dsoRules = sv1Backend.getDsoInfo().dsoRules
    val sv1Party = sv1Backend.getDsoInfo().svParty

    val description = UUID.randomUUID().toString

    val voteRequestCid = clue("request vote for config schedule change") {
      val (_, voteRequestCid) = actAndCheck(
        "sv1 creates a vote request", {
          sv1Backend.createVoteRequest(
            sv1Party.toProtoPrimitive,
            action,
            "url",
            description,
            new RelTime(expiration.toMillis),
            effectivity match {
              case None => None
              case Some(effectivity) => Some(env.environment.clock.now.add(effectivity).toInstant)
            },
          )
        },
      )(
        "The vote request has been created and sv1 accepts",
        _ => {
          sv1Backend.listVoteRequests() should not be empty
          val head =
            sv1Backend.listVoteRequests().filter(_.payload.reason.body == description).loneElement
          sv1Backend.lookupVoteRequest(head.contractId).payload.votes should have size 1
          head
        },
      )
      voteRequestCid
    }
    castVotes(voteRequestCid, dsoRules.contract, accept)
  }

  def castVotes(
      voteRequestCid: Contract[VoteRequest.ContractId, VoteRequest],
      dsoRules: Contract[DsoRules.ContractId, DsoRules],
      accept: Boolean,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): Unit = {
    clue("cast votes for config schedule change") {
      var voteCount: Long = 1
      env.svs.local
        .foreach(sv =>
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
                  accept,
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
        )
    }
  }

}
