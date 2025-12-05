package org.lfdecentralizedtrust.splice.util

import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.ledger.javaapi.data.Transaction
import org.lfdecentralizedtrust.splice.codegen.java.splice.issuance.IssuanceConfig
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_ModifyValidatorLicenses,
  SRARC_SetConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRulesConfig,
  DsoRules_SetConfig,
  SynchronizerUpgradeSchedule,
  ValidatorLicenseChange,
  ValidatorLicensesModification,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.console.{
  ParticipantClientReference,
  ScanAppBackendReference,
  SvAppBackendReference,
  SvAppReference,
  WalletAppClientReference,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  SpliceTestConsoleEnvironment,
  TestCommon,
}
import org.lfdecentralizedtrust.splice.util.SvTestUtil.ConfirmingSv
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import org.scalatest.Assertion

import java.math.RoundingMode
import java.util.Optional
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.control.NonFatal

trait SvTestUtil extends TestCommon {
  this: CommonAppInstanceReferences =>

  protected def svs(implicit env: SpliceTestConsoleEnvironment) =
    Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)

  def allocateRandomSvParty(name: String, id: Option[Int] = None)(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val enumerator = id.getOrElse((new scala.util.Random).nextInt()).toHexString
    val partyIdHint = s"$name-$enumerator"
    eventuallySucceeds() {
      try { sv1Backend.participantClient.ledger_api.parties.allocate(partyIdHint).party }
      catch {
        case NonFatal(e) =>
          sv1Backend.participantClient.ledger_api.parties
            .list()
            .find(_.party.toProtoPrimitive.startsWith(partyIdHint))
            .getOrElse(fail(e))
            .party
      }
    }
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

  def confirmActionByAllSvs(
      confirmingSvs: Seq[ConfirmingSv],
      action: ActionRequiringConfirmation,
  )(implicit env: SpliceTestConsoleEnvironment): Seq[Transaction] = {
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
    svBackends.map(sv => ConfirmingSv(sv.participantClientWithAdminToken, sv.getDsoInfo().svParty))

  def getSvName(sv: Integer): String =
    if (sv == 1) {
      "Digital-Asset-2"
    } else
      s"Digital-Asset-Eng-${sv}"

  def scheduleDomainMigration(
      svToCreateVoteRequest: SvAppReference,
      svsToCastVotes: Seq[SvAppReference],
      domainUpgradeSchedule: Option[SynchronizerUpgradeSchedule],
  )(implicit
      ec: ExecutionContextExecutor
  ): Unit = {
    val dsoRules = svToCreateVoteRequest.getDsoInfo().dsoRules
    val action = new ARC_DsoRules(
      new SRARC_SetConfig(
        new DsoRules_SetConfig(
          updateNextScheduledSynchronizerUpgrade(dsoRules.payload.config, domainUpgradeSchedule),
          Optional.empty(),
        )
      )
    )

    actAndCheck(timeUntilSuccess = 60.seconds)(
      "Voting on an DsoRules config change for scheduled migration", {
        def onlySetConfigVoteRequests(
            voteRequests: Seq[Contract[VoteRequest.ContractId, VoteRequest]]
        ) =
          voteRequests.filter {
            _.payload.action match {
              case action: ARC_DsoRules =>
                action.dsoAction match {
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
              svToCreateVoteRequest.getDsoInfo().svParty.toProtoPrimitive,
              action,
              "url",
              "description",
              // We give everyone 30 seconds to vote. Hopefully that is a good sweet spot between
              // the vote failing because of a timeout and the test taking too long.
              new RelTime(30 * 1000 * 1000),
              None,
            )
          },
        )(
          "vote request has been created",
          _ => onlySetConfigVoteRequests(svToCreateVoteRequest.listVoteRequests()).loneElement,
        )

        // Concurrent uses of log suppression don't work so we suppress here instead of within eventually succeeds
        loggerFactory.suppressErrors {
          svsToCastVotes.parTraverse { sv =>
            Future {
              clue(s"${svsToCastVotes.map(_.name)} see the vote request") {
                val svVoteRequest = eventually() {
                  onlySetConfigVoteRequests(sv.listVoteRequests()).loneElement
                }
                getTrackingId(svVoteRequest) shouldBe voteRequest.contractId
              }
              clue(s"${sv.name} accepts vote") {
                eventually() {
                  try {
                    sv.castVote(
                      voteRequest.contractId,
                      true,
                      "url",
                      "description",
                    )
                  } catch {
                    case NonFatal(e) => fail(e)
                  }
                }
              }
            }
          }.futureValue
        }
      },
    )(
      "observing DsoRules with changed config",
      _ => {
        val newDsoRules = svToCreateVoteRequest.getDsoInfo().dsoRules
        newDsoRules.payload.config.nextScheduledSynchronizerUpgrade.toScala shouldBe domainUpgradeSchedule
      },
    )
  }

  def modifyValidatorLicenses(
      svToCreateVoteRequest: SvAppBackendReference,
      svsToCastVotes: Seq[SvAppBackendReference],
      changes: Seq[ValidatorLicenseChange],
  ): Unit = {
    val action = new ARC_DsoRules(
      new SRARC_ModifyValidatorLicenses(
        new ValidatorLicensesModification(changes.asJava)
      )
    )

    def onlyModifyValidatorLicensesVoteRequests(
        voteRequests: Seq[Contract[VoteRequest.ContractId, VoteRequest]]
    ) =
      voteRequests.filter {
        _.payload.action match {
          case arc: ARC_DsoRules =>
            arc.dsoAction match {
              case _: SRARC_ModifyValidatorLicenses => true
              case _ => false
            }
          case _ => false
        }
      }

    val (_, voteRequest) = actAndCheck(
      "Creating vote request",
      svToCreateVoteRequest.createVoteRequest(
        svToCreateVoteRequest.getDsoInfo().svParty.toProtoPrimitive,
        action,
        "url",
        "description",
        svToCreateVoteRequest.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
        None,
      ),
    )(
      "vote request has been created",
      _ =>
        onlyModifyValidatorLicensesVoteRequests(
          svToCreateVoteRequest.listVoteRequests()
        ).loneElement,
    )

    svsToCastVotes.foreach { sv =>
      clue(s"${sv.name} sees the vote request") {
        val svVoteRequest = eventually() {
          onlyModifyValidatorLicensesVoteRequests(sv.listVoteRequests()).loneElement
        }
        getTrackingId(svVoteRequest) shouldBe voteRequest.contractId
      }
      sv.castVote(
        voteRequest.contractId,
        true,
        "url",
        "description",
      )
    }
  }

  private def confirmAction(
      participantClient: ParticipantClientReference,
      svPartyId: PartyId,
      action: ActionRequiringConfirmation,
  )(implicit env: SpliceTestConsoleEnvironment): Transaction = {
    eventuallySucceeds() {
      val dsoRulesCid = participantClient.ledger_api_extensions.acs
        .filterJava(splice.dsorules.DsoRules.COMPANION)(dsoParty)
        .head
        .id
      participantClient.ledger_api_extensions.commands
        .submitJava(
          actAs = Seq(svPartyId),
          readAs = Seq(dsoParty),
          commands = dsoRulesCid
            .exerciseDsoRules_ConfirmAction(svPartyId.toProtoPrimitive, action)
            .commands
            .asScala
            .toSeq,
        )
    }
  }

  private def updateNextScheduledSynchronizerUpgrade(
      dsoRulesConfig: DsoRulesConfig,
      domainUpgradeSchedule: Option[SynchronizerUpgradeSchedule],
  ) = new DsoRulesConfig(
    dsoRulesConfig.numUnclaimedRewardsThreshold,
    dsoRulesConfig.numMemberTrafficContractsThreshold,
    dsoRulesConfig.actionConfirmationTimeout,
    dsoRulesConfig.svOnboardingRequestTimeout,
    dsoRulesConfig.svOnboardingConfirmedTimeout,
    dsoRulesConfig.voteRequestTimeout,
    dsoRulesConfig.dsoDelegateInactiveTimeout,
    dsoRulesConfig.synchronizerNodeConfigLimits,
    dsoRulesConfig.maxTextLength,
    dsoRulesConfig.decentralizedSynchronizer,
    domainUpgradeSchedule.toJava,
    dsoRulesConfig.voteCooldownTime,
    dsoRulesConfig.voteExecutionInstructionTimeout,
  )

  def computeAmuletsToIssueToSvs(
      config: IssuanceConfig,
      tickDuration: NonNegativeFiniteDuration,
  ): java.math.BigDecimal = {
    val RoundsPerYear =
      BigDecimal(365 * 24 * 60 * 60).bigDecimal
        .divide(BigDecimal(tickDuration.duration.toSeconds).bigDecimal)
    config.amuletToIssuePerYear
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
      dsoSize: Int,
  ): java.math.BigDecimal = {
    computeAmuletsToIssueToSvs(config, tickDuration)
      .divide(BigDecimal(dsoSize).bigDecimal, RoundingMode.HALF_UP)
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
  case class ConfirmingSv(svParticipantClient: ParticipantClientReference, svPartyId: PartyId)
}
