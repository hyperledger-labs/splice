package com.daml.network.integration.tests

import com.daml.ledger.javaapi.data.{Identifier as JIdentifier, Template}
import com.daml.ledger.javaapi.data.codegen.{
  ContractId,
  Update,
  Contract as CodegenContract,
  ContractCompanion as TemplateCompanion,
}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cn.{svcrules as svcr, svonboarding as so}
import com.daml.network.codegen.java.da.types.Tuple2
import com.daml.network.config.CNNodeConfigTransforms.updateAllAutomationConfigs
import com.daml.network.store.MultiDomainAcsStore.ContractState.Assigned
import com.daml.network.util.{
  AssignedContract,
  CoinConfigSchedule,
  ConfigScheduleUtil,
  ContractWithState,
}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.topology.PartyId
import org.scalatest.prop.TableDrivenPropertyChecks.forEvery as tForEvery

import java.time.Instant
import scala.jdk.CollectionConverters.*

/** You must `start-canton` with `-g` to run this test locally. */
class GlobalDomainUpgradeTimeBasedIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithIsolatedEnvironment
    with ConfigScheduleUtil {
  override def environmentDefinition =
    super.environmentDefinition.addConfigTransforms { (_, config) =>
      updateAllAutomationConfigs(c =>
        c.copy(
          // Need to disable triggers so workflows stay open
          enableSvcGovernance = false,
          enableClosedRoundArchival = false,
          enableSvRewards = false,
        )
      )(config)
    }

  private[this] val globalUpgradeDomain = DomainAlias.tryCreate("global-upgrade")

  "scheduled global domain upgrade happens" in { implicit env =>
    initSvcWithSv1Only() withClue "spin up Svc"

    val timeUntilNewRule = defaultTickDuration
    val timeToWaitForNewRule = tickDurationWithBuffer

    // If you fail here, see class scaladoc.
    val globalUpgradeId = sv1Backend.participantClient.domains.id_of(
      globalUpgradeDomain
    ) withClue "find the global-upgrade domain ID"

    val (previousGlobalId, coinRulesCid) = clue("change coinconfig to migrate domains") {
      inside(sv1ScanBackend.getCoinRules()) {
        case ContractWithState(firstCoinRules, Assigned(global1)) =>
          val now = sv1Backend.participantClientWithAdminToken.ledger_api.time.get()
          val currentSchedule = firstCoinRules.payload.configSchedule
          val activeDomainId =
            CoinConfigSchedule(currentSchedule).getConfigAsOf(now).globalDomain.activeDomain

          globalUpgradeId.toProtoPrimitive should not be activeDomainId
          global1.toProtoPrimitive shouldBe activeDomainId

          val upgradeAfterTick = new Tuple2(
            env.environment.clock.now.add(timeUntilNewRule.asJava).toInstant,
            mkUpdatedCoinConfig(
              currentSchedule,
              defaultTickDuration,
              nextDomainId = Some(globalUpgradeId),
            ),
          )

          val setScheduleResult = cleanAndAddNewSchedule(
            AssignedContract(firstCoinRules, global1),
            upgradeAfterTick,
          ) withClue "set config schedule with upgraded domain"

          eventually() {
            inside(sv1ScanBackend.getCoinRules()) { case ContractWithState(secondCoinRules, _) =>
              secondCoinRules.contractId should not be firstCoinRules.contractId
              setScheduleResult shouldBe secondCoinRules.contractId
            }
          }
          (global1, setScheduleResult)
      }
    }

    val svcRulesCid = sv1Backend.getSvcInfo().svcRules.contractId

    def nonEmptyOnSv1[
        TC <: CodegenContract[TCid, T],
        TCid <: ContractId[T],
        T <: Template,
    ](companion: TemplateCompanion[TC, TCid, T]) =
      sv1Backend.participantClient.ledger_api_extensions.acs
        .filterJava(companion)(svcParty)
        .nonEmpty

    val sv1Party = sv1Backend.getSvcInfo().svParty

    def exerciseSvc[T](update: Update[T]) =
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          userId = sv1Backend.config.ledgerApiUser,
          actAs = Seq(svcParty, sv1Party),
          readAs = Seq.empty,
          update = update,
          domainId = Some(previousGlobalId),
        )

    clue("create governance contracts of various kinds") {
      import com.daml.network.codegen.java.cc.api.v1.round
      actAndCheck(
        "create VoteRequest",
        sv1Backend.createVoteRequest(
          sv1Party.toProtoPrimitive,
          new svcr.actionrequiringconfirmation.ARC_SvcRules(
            new svcr.svcrules_actionrequiringconfirmation.SRARC_AddMember(
              new svcr.SvcRules_AddMember(
                "alice",
                "Alice",
                "alice-participant-id",
                new round.Round(42),
                previousGlobalId.toProtoPrimitive,
              )
            )
          ),
          "url",
          "description",
          sv1Backend.getSvcInfo().svcRules.payload.config.voteRequestTimeout,
        ),
      )(
        "VoteRequest and Vote should be there",
        _ =>
          inside(sv1Backend.listVoteRequests()) { case Seq(onlyReq) =>
            sv1Backend.listVotes(Vector(onlyReq.contractId.contractId)) should have size 1
          },
      )

      actAndCheck(
        "create sample ElectionRequest",
        exerciseSvc(
          svcRulesCid.exerciseSvcRules_RequestElection(
            sv1Party.toProtoPrimitive,
            new svcr.electionrequestreason.ERR_OtherReason("watch the request get migrated"),
            Seq(sv1Party.toProtoPrimitive).asJava,
          )
        ),
      )("ElectionRequest should be there", _ => nonEmptyOnSv1(svcr.ElectionRequest.COMPANION))

      actAndCheck(
        "create sample Confirmation",
        exerciseSvc(
          svcRulesCid.exerciseSvcRules_ConfirmAction(
            sv1Party.toProtoPrimitive,
            new svcr.actionrequiringconfirmation.ARC_SvcRules(
              new svcr.svcrules_actionrequiringconfirmation.SRARC_RemoveMember(
                new svcr.SvcRules_RemoveMember("nonsense")
              )
            ),
          )
        ),
      )("ensure Confirmation is there", _ => nonEmptyOnSv1(svcr.Confirmation.COMPANION))

      actAndCheck(
        "create sample SvOnboardingRequest",
        exerciseSvc(
          svcRulesCid.exerciseSvcRules_StartSvOnboarding(
            "irrelevant name",
            sv1Party.toProtoPrimitive, // irrelevant party
            "irrelevant token",
            sv1Party.toProtoPrimitive,
          )
        ),
      )("ensure SvOnboardingRequest is there", _ => nonEmptyOnSv1(so.SvOnboardingRequest.COMPANION))

      actAndCheck(
        "create sample ApprovedSvIdentity",
        exerciseSvc(
          new so.ApprovedSvIdentity(sv1Party.toProtoPrimitive, "irrelevant name", "irrelevant key")
            .create()
        ),
      )("ensure ApprovedSvIdentity is there", _ => nonEmptyOnSv1(so.ApprovedSvIdentity.COMPANION))

      actAndCheck(
        "create sample SvReward",
        exerciseSvc(
          new svcr.SvReward(
            svcParty.toProtoPrimitive,
            sv1Party.toProtoPrimitive,
            new cc.api.v1.round.Round(42),
            new java.math.BigDecimal("42"),
          ).create()
        ),
      )("ensure SvReward is there", _ => nonEmptyOnSv1(svcr.SvReward.COMPANION))

      actAndCheck(
        "create sample SvOnboardingConfirmed",
        exerciseSvc(
          new so.SvOnboardingConfirmed(
            sv1Party.toProtoPrimitive,
            "irrelevant name",
            "observing domain migration",
            svcParty.toProtoPrimitive,
            com.daml.lf.data.Time.Timestamp.MaxValue.toInstant,
          ).create()
        ),
      )(
        "ensure SvOnboardingConfirmed is there",
        _ => nonEmptyOnSv1(so.SvOnboardingConfirmed.COMPANION),
      )
    }

    clue("create svc-signed coin contracts of various kinds") {
      val (oldestRound, newestRound) = {
        val rounds = sv1ScanBackend.getOpenAndIssuingMiningRounds()._1
        (rounds.headOption.value, rounds.lastOption.value)
      }

      actAndCheck(
        "create sample IssuingMiningRound",
        exerciseSvc(
          new cc.round.IssuingMiningRound(
            svcParty.toProtoPrimitive,
            new cc.api.v1.round.Round(42),
            new java.math.BigDecimal(42),
            new java.math.BigDecimal(42),
            new java.math.BigDecimal(42),
            oldestRound.payload.opensAt,
            newestRound.payload.targetClosesAt,
          ).create()
        ),
      )(
        "ensure IssuingMiningRound is there",
        _ => nonEmptyOnSv1(cc.round.IssuingMiningRound.COMPANION),
      )

      actAndCheck(
        "create sample ClosedMiningRound",
        exerciseSvc(
          new cc.round.ClosedMiningRound(
            svcParty.toProtoPrimitive,
            new cc.api.v1.round.Round(42),
            new java.math.BigDecimal(42),
            new java.math.BigDecimal(42),
            new java.math.BigDecimal(42),
          )
            .create()
        ),
      )(
        "ensure ClosedMiningRound is there",
        _ => nonEmptyOnSv1(cc.round.ClosedMiningRound.COMPANION),
      )

      actAndCheck(
        "create sample FeaturedAppRight",
        exerciseSvc(coinRulesCid.exerciseCoinRules_DevNet_FeatureApp(sv1Party.toProtoPrimitive)),
      )("ensure FeaturedAppRight is there", _ => nonEmptyOnSv1(cc.coin.FeaturedAppRight.COMPANION))

      actAndCheck(
        "create sample UnclaimedReward",
        exerciseSvc(
          new cc.coin.UnclaimedReward(svcParty.toProtoPrimitive, new java.math.BigDecimal(42))
            .create()
        ),
      )("ensure UnclaimedReward is there", _ => nonEmptyOnSv1(cc.coin.UnclaimedReward.COMPANION))
    }

    advanceTime(timeToWaitForNewRule) withClue "advance time"

    clue("see whether the svcrules moves") {
      eventually() {
        val cid: LfContractId = svcRulesCid
        sv1ValidatorBackend.participantClient.transfer.lookup_contract_domain(cid) shouldBe Map(
          cid -> globalUpgradeDomain
        )
      }
    }

    advanceTimeByPollingInterval(sv1Backend) withClue "advancing time so follow trigger happens"

    clue("see whether coinrules follows svcrules") {
      eventually() {
        sv1ScanBackend.getCoinRules().state shouldBe Assigned(globalUpgradeId)
      }
    }

    import language.existentials
    type FilterableCompanion =
      TemplateCompanion[_ <: CodegenContract[TCid, Data], TCid, Data] forSome {
        type Data <: Template
        type TCid <: ContractId[Data]
      }

    def c(fc: FilterableCompanion, queryParty: PartyId = svcParty) =
      (fc, queryParty)

    def allContractsMigrated(rows: (FilterableCompanion, PartyId)*) = {
      val companions = Table[JIdentifier, FilterableCompanion, PartyId](
        ("template", "companion", "querying party"),
        rows.map { case (c, p) => (c.TEMPLATE_ID, c, p) }: _*
      )
      eventually() {
        tForEvery(companions) { (_, companion, queryingParty) =>
          val contractIds = sv1Backend.participantClient.ledger_api_extensions.acs
            .filterJava(companion)(queryingParty)
            .map(_.id: LfContractId)
          contractIds should not be empty
          val domains =
            sv1ValidatorBackend.participantClient.transfer.lookup_contract_domain(contractIds: _*)

          tForEvery(Table("contract ID", contractIds: _*)) { cid =>
            domains.get(cid) shouldBe Some(globalUpgradeDomain.unwrap)
          }
        }
      }
    }

    clue("see whether governance contracts follow svcrules") {
      allContractsMigrated(
        c(svcr.Vote.COMPANION),
        c(svcr.VoteRequest.COMPANION),
        c(svcr.Confirmation.COMPANION),
        c(svcr.SvReward.COMPANION),
        c(svcr.ElectionRequest.COMPANION),
        c(so.ApprovedSvIdentity.COMPANION, sv1Party), // only has the sv party as a stakeholder
        c(so.SvOnboardingRequest.COMPANION),
        c(so.SvOnboardingConfirmed.COMPANION),
      )
    }

    // wait a tick for next, as below wait for CoinRules to move
    advanceTime(tickDurationWithBuffer)

    clue(
      "see whether coin contracts signed only by SVC (in particular mining rounds) follow svcrules"
    ) {
      allContractsMigrated(
        c(cc.round.OpenMiningRound.COMPANION),
        c(cc.round.SummarizingMiningRound.COMPANION),
        c(cc.round.IssuingMiningRound.COMPANION),
        c(cc.round.ClosedMiningRound.COMPANION),
        c(cc.coin.FeaturedAppRight.COMPANION),
        // TODO (#7210) c(cc.coin.SvcReward.COMPANION),
        c(cc.coin.UnclaimedReward.COMPANION),
        c(cc.validatorlicense.ValidatorLicense.COMPANION),
      )
    }

  // check scan for other contracts' transfer:
  // TODO (#7468) check coin and wallet contracts
  // TODO (#5959) check directory contracts
  }

  private[this] def cleanAndAddNewSchedule(
      start: AssignedContract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules],
      newSchedule: Tuple2[Instant, cc.coinconfig.CoinConfig[cc.coinconfig.USD]],
  )(implicit fp: FixtureParam): cc.coin.CoinRules.ContractId = {
    sv1ScanBackend
      .getCoinRules()
      .payload
      .configSchedule
      .futureValues
      .forEach(config => {
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitWithResult(
            userId = sv1Backend.config.ledgerApiUser,
            actAs = Seq(svcParty),
            readAs = Seq.empty,
            update = start.contractId
              .exerciseCoinRules_RemoveFutureCoinConfigSchedule(config._1),
            domainId = Some(start.domain),
          )
          .exerciseResult
      })
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitWithResult(
        userId = sv1Backend.config.ledgerApiUser,
        actAs = Seq(svcParty),
        readAs = Seq.empty,
        update = start.contractId.exerciseCoinRules_AddFutureCoinConfigSchedule(newSchedule),
        domainId = Some(start.domain),
      )
      .exerciseResult
  }
}
