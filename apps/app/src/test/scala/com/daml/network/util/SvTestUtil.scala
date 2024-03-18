package com.daml.network.util

import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.ledger.javaapi.data.TransactionTree
import com.daml.network.codegen.java.cc.issuance.IssuanceConfig
import com.daml.network.codegen.java.cn
import com.daml.network.codegen.java.cn.svcrules.actionrequiringconfirmation.ARC_SvcRules
import com.daml.network.codegen.java.cn.svcrules.svcrules_actionrequiringconfirmation.SRARC_SetConfig
import com.daml.network.codegen.java.cn.svcrules.{
  ActionRequiringConfirmation,
  DomainUpgradeSchedule,
  SvcRulesConfig,
  SvcRules_SetConfig,
  VoteRequest,
}
import com.daml.network.codegen.java.da.time.types.RelTime
import com.daml.network.console.{
  CNParticipantClientReference,
  ScanAppBackendReference,
  SvAppBackendReference,
  SvAppReference,
  WalletAppClientReference,
}
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.SvTestUtil.ConfirmingSv
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import org.scalatest.Assertion

import java.math.RoundingMode
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait SvTestUtil extends CNNodeTestCommon {
  this: CommonCNNodeAppInstanceReferences =>

  protected def svs(implicit env: CNNodeTestConsoleEnvironment) =
    Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)

  def allocateRandomSvParty(name: String)(implicit env: CNNodeTestConsoleEnvironment) = {
    val id = (new scala.util.Random).nextInt().toHexString
    sv1Backend.participantClient.ledger_api.parties.allocate(s"$name-$id", name).party
  }

  def median(values: Seq[BigDecimal]): Option[BigDecimal] = {
    if (values != null && values.length > 0) {
      val sorted = values.sorted(Ordering[BigDecimal])
      val length = sorted.length
      val half = length / 2
      if (length % 2 != 0)
        Some(sorted(half))
      else
        Some((sorted(half - 1) + sorted(half)) / BigDecimal.valueOf(2))
    } else {
      None
    }
  }

  def confirmActionByAllMembers(
      confirmingSvs: Seq[ConfirmingSv],
      action: ActionRequiringConfirmation,
  )(implicit env: CNNodeTestConsoleEnvironment): Seq[TransactionTree] = {
    confirmingSvs.map { case ConfirmingSv(participantClient, svPartyId) =>
      confirmAction(participantClient, svPartyId, action)
    }
  }

  def getTrackingId(
      voteRequest: Contract[VoteRequest.ContractId, VoteRequest]
  ): VoteRequest.ContractId = {
    voteRequest.payload.trackingCid.toScala
      .getOrElse(voteRequest.contractId)
  }

  def getConfirmingSvs(svBackends: Seq[SvAppBackendReference]): Seq[ConfirmingSv] =
    svBackends.map(sv => ConfirmingSv(sv.participantClientWithAdminToken, sv.getSvcInfo().svParty))

  def getSvName(sv: Integer): String =
    if (sv == 1) {
      "Digital-Asset-2"
    } else
      s"Digital-Asset-Eng-${sv}"

  def scheduleDomainMigration(
      svToCreateVoteRequest: SvAppReference,
      svsToCastVotes: Seq[SvAppReference],
      domainUpgradeSchedule: Option[DomainUpgradeSchedule],
  )(implicit
      ec: ExecutionContextExecutor
  ): Unit = {
    val svcRules = svToCreateVoteRequest.getSvcInfo().svcRules
    val action = new ARC_SvcRules(
      new SRARC_SetConfig(
        new SvcRules_SetConfig(
          updateNextScheduledDomainUpgrade(svcRules.payload.config, domainUpgradeSchedule)
        )
      )
    )

    actAndCheck(timeUntilSuccess = 60.seconds)(
      "Voting on an SvcRules config change for scheduled migration", {
        def onlySetConfigVoteRequests(
            voteRequests: Seq[Contract[VoteRequest.ContractId, VoteRequest]]
        ) =
          voteRequests.filter {
            _.payload.action match {
              case action: ARC_SvcRules =>
                action.svcAction match {
                  case _: SRARC_SetConfig => true
                  case _ => false
                }
              case _ => false
            }
          }

        val (_, voteRequest) = actAndCheck(
          "Creating vote request",
          eventuallySucceeds() {
            svToCreateVoteRequest.createVoteRequest(
              svToCreateVoteRequest.getSvcInfo().svParty.toProtoPrimitive,
              action,
              "url",
              "description",
              // We give everyone 30 seconds to vote. Hopefully that is a good sweet spot between
              // the vote failing because of a timeout and the test taking too long.
              new RelTime(30 * 1000 * 1000),
            )
          },
        )(
          "vote request has been created",
          _ => onlySetConfigVoteRequests(svToCreateVoteRequest.listVoteRequests()).loneElement,
        )

        svsToCastVotes.parTraverse { sv =>
          Future {
            clue(s"${svsToCastVotes.map(_.name)} see the vote request") {
              val svVoteRequest = eventually() {
                onlySetConfigVoteRequests(sv.listVoteRequests()).loneElement
              }
              getTrackingId(svVoteRequest) shouldBe voteRequest.contractId
            }
            clue(s"${sv.name} accepts vote") {
              eventuallySucceeds() {
                sv.castVote(
                  voteRequest.contractId,
                  true,
                  "url",
                  "description",
                )
              }
            }
          }
        }.futureValue
      },
    )(
      "observing SvcRules with changed config",
      _ => {
        val newSvcRules = svToCreateVoteRequest.getSvcInfo().svcRules
        newSvcRules.payload.config.nextScheduledDomainUpgrade.toScala shouldBe domainUpgradeSchedule
      },
    )
  }

  private def confirmAction(
      participantClient: CNParticipantClientReference,
      svPartyId: PartyId,
      action: ActionRequiringConfirmation,
  )(implicit env: CNNodeTestConsoleEnvironment): TransactionTree = {
    eventuallySucceeds() {
      val svcRulesCid = participantClient.ledger_api_extensions.acs
        .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
        .head
        .id
      participantClient.ledger_api_extensions.commands
        .submitJava(
          actAs = Seq(svPartyId),
          readAs = Seq(svcParty),
          optTimeout = None,
          commands = svcRulesCid
            .exerciseSvcRules_ConfirmAction(svPartyId.toProtoPrimitive, action)
            .commands
            .asScala
            .toSeq,
        )
    }
  }

  private def updateNextScheduledDomainUpgrade(
      svcRulesConfig: SvcRulesConfig,
      domainUpgradeSchedule: Option[DomainUpgradeSchedule],
  ) = new SvcRulesConfig(
    svcRulesConfig.numUnclaimedRewardsThreshold,
    svcRulesConfig.numMemberTrafficContractsThreshold,
    svcRulesConfig.actionConfirmationTimeout,
    svcRulesConfig.svOnboardingRequestTimeout,
    svcRulesConfig.svOnboardingConfirmedTimeout,
    svcRulesConfig.voteRequestTimeout,
    svcRulesConfig.leaderInactiveTimeout,
    svcRulesConfig.domainNodeConfigLimits,
    svcRulesConfig.maxTextLength,
    svcRulesConfig.globalDomain,
    domainUpgradeSchedule.toJava,
  )

  def computeCoinsToIssueToSvc(
      config: IssuanceConfig,
      tickDuration: NonNegativeFiniteDuration,
  ): java.math.BigDecimal = {
    val RoundsPerYear =
      BigDecimal(365 * 24 * 60 * 60).bigDecimal
        .divide(BigDecimal(tickDuration.duration.toSeconds).bigDecimal)
    config.coinToIssuePerYear
      .multiply(
        BigDecimal(1.0).bigDecimal
          .subtract(config.appRewardPercentage)
          .subtract(config.validatorRewardPercentage)
      )
      .divide(RoundsPerYear, RoundingMode.HALF_UP)
  }

  /** This assumes the weight is equal across all SVs.
    */
  def computeSvRewardInRound0(
      config: IssuanceConfig,
      tickDuration: NonNegativeFiniteDuration,
      svcSize: Int,
  ): java.math.BigDecimal = {
    computeCoinsToIssueToSvc(config, tickDuration)
      .divide(BigDecimal(svcSize).bigDecimal, RoundingMode.HALF_UP)
      .setScale(10, RoundingMode.HALF_UP)
  }

  def ensureSvRewardCouponReceivedForCurrentRound(
      scan: ScanAppBackendReference,
      wallet: WalletAppClientReference,
  ): Assertion = {
    val currentRound =
      scan.getOpenAndIssuingMiningRounds()._1.head.contract.payload.round.number
    wallet
      .listSvRewardCoupons()
      .map(_.payload.round.number) should contain(currentRound)
  }

  def ensureNoSvRewardCouponExistsForRound(
      round: Long,
      wallet: WalletAppClientReference,
  ): Assertion = {
    inside(
      wallet
        .listSvRewardCoupons()
    ) { case coupons =>
      coupons.map(_.payload.round.number) should not(contain(round))
    }
  }
}

object SvTestUtil {
  case class ConfirmingSv(svParticipantClient: CNParticipantClientReference, svPartyId: PartyId)
}
