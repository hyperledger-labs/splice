package com.daml.network.integration.tests

import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{
  Contract as CodegenContract,
  ContractCompanion as TemplateCompanion,
  ContractId,
}
import com.daml.network.store.MultiDomainAcsStore.ContractState
import ContractState.Assigned
import com.daml.network.codegen.java.cn.svcrules as svcr
import com.daml.network.codegen.java.cn.svonboarding as so
import com.daml.network.util.{CoinConfigSchedule, ConfigScheduleUtil, ContractWithState}
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.DomainAlias
import org.scalatest.prop.TableDrivenPropertyChecks.forEvery as tForEvery

/** You must `start-canton` with `-g` to run this test locally. */
class GlobalDomainUpgradeTimeBasedIntegrationTest
    extends SvTimeBasedIntegrationTestBaseWithIsolatedEnvironment
    with ConfigScheduleUtil {
  private[this] val globalUpgradeDomain = DomainAlias.tryCreate("global-upgrade")

  "scheduled global domain upgrade happens" in { implicit env =>
    initSvcWithSv1Only() withClue "spin up Svc"

    val timeUntilNewRule = defaultTickDuration
    val timeToWaitForNewRule = tickDurationWithBuffer

    // If you fail here, see class scaladoc.
    val globalUpgradeId = sv1Backend.participantClient.domains.id_of(
      globalUpgradeDomain
    ) withClue "find the global-upgrade domain ID"

    clue("change coinconfig to migrate domains") {
      inside(sv1ScanBackend.getCoinRules()) {
        case ContractWithState(firstCoinRules, Assigned(global1)) =>
          val now = sv1Backend.participantClientWithAdminToken.ledger_api.time.get()
          val currentSchedule = firstCoinRules.payload.configSchedule
          val activeDomainId =
            CoinConfigSchedule(currentSchedule).getConfigAsOf(now).globalDomain.activeDomain

          globalUpgradeId.toProtoPrimitive should not be activeDomainId
          global1.toProtoPrimitive shouldBe activeDomainId

          val upgradeAfterTick = createConfigSchedule(
            currentSchedule,
            (
              timeUntilNewRule.asJava,
              mkUpdatedCoinConfig(
                currentSchedule,
                defaultTickDuration,
                nextDomainId = Some(globalUpgradeId),
              ),
            ),
          )

          eventually() { // see https://github.com/DACH-NY/canton-network-node/pull/5518#discussion_r1233108607
            setConfigSchedule(upgradeAfterTick)
          } withClue "set config schedule with upgraded domain"

          eventually() {
            inside(sv1ScanBackend.getCoinRules()) { case ContractWithState(secondCoinRules, _) =>
              secondCoinRules.contractId should not be firstCoinRules.contractId
            }
          }
      }
    }

    val svcRulesCid = sv1Backend.getSvcInfo().svcRules.contractId

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

    clue("see whether governance contracts follow svcrules") {
      // TODO (#6459) ensure >=1 contracts of each below template are reassigned
      import language.existentials
      type FilterableCompanion =
        TemplateCompanion[_ <: CodegenContract[TCid, Data], TCid, Data] forSome {
          type Data <: Template
          type TCid <: ContractId[Data]
        }
      val companions = Table[FilterableCompanion](
        "companion",
        svcr.Vote.COMPANION,
        svcr.VoteRequest.COMPANION,
        svcr.Confirmation.COMPANION,
        svcr.SvReward.COMPANION,
        svcr.ElectionRequest.COMPANION,
        so.ApprovedSvIdentity.COMPANION, // only has the sv party as a stakeholder
        so.SvOnboardingRequest.COMPANION,
        so.SvOnboardingConfirmed.COMPANION,
      )
      eventually() {
        tForEvery(companions) { companion =>
          val contractIds = sv1Backend.participantClient.ledger_api_extensions.acs
            .filterJava(companion)(svcParty)
            .map(_.id: LfContractId)
          val domains =
            sv1ValidatorBackend.participantClient.transfer.lookup_contract_domain(contractIds: _*)
          tForEvery(Table("contract ID", contractIds: _*)) { cid =>
            domains.get(cid) shouldBe Some(globalUpgradeId)
          }
        }
      }
    }
  // check scan for other contracts' transfer:
  // TODO (#5957) check coin contracts signed only by SVC (in particular mining rounds)
  // TODO (#5958) check coin and wallet contracts
  // TODO (#5959) check directory contracts
  }
}
