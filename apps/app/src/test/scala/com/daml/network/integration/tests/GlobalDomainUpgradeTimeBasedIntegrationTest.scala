package com.daml.network.integration.tests

import com.daml.network.store.MultiDomainAcsStore.{ContractState, ContractWithState}
import ContractState.Assigned
import com.daml.network.util.{CoinConfigSchedule, ConfigScheduleUtil}
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.DomainAlias

/** You must `start-canton` with `-g` to run this test locally. */
class GlobalDomainUpgradeTimeBasedIntegrationTest
    extends SvTimeBasedIntegrationTestBase
    with ConfigScheduleUtil {
  private[this] val globalUpgradeDomain = DomainAlias.tryCreate("global-upgrade")

  "scheduled global domain upgrade happens" in { implicit env =>
    initSvcWithSv1Only() withClue "spin up Svc"

    val timeUntilNewRule = defaultTickDuration
    val timeToWaitForNewRule = tickDurationWithBuffer

    // If you fail here, see class scaladoc.
    val globalUpgradeId = sv1.participantClient.domains.id_of(
      globalUpgradeDomain
    ) withClue "find the global-upgrade domain ID"

    clue("change coinconfig to migrate domains") {
      inside(sv1Scan.getCoinRules()) { case ContractWithState(firstCoinRules, Assigned(global1)) =>
        val now = sv1.participantClientWithAdminToken.ledger_api.time.get()
        val currentSchedule = firstCoinRules.payload.configSchedule
        val activeDomainId =
          CoinConfigSchedule(currentSchedule).getConfigAsOf(now).globalDomain.activeDomain

        globalUpgradeId.toProtoPrimitive should not be activeDomainId
        global1.toProtoPrimitive shouldBe activeDomainId

        eventually() {
          svcClient.health.initialized() shouldBe true
          svcClient.health.running() shouldBe true
        }

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
      }
    }

    val svcRulesCid = sv1.getSvcInfo().svcRules.contractId

    advanceTime(timeToWaitForNewRule) withClue "advance time"

    clue("see whether the svcrules moves") {
      eventually() {
        val cid: LfContractId = svcRulesCid
        sv1Validator.participantClient.transfer.lookup_contract_domain(cid) shouldBe Map(
          cid -> globalUpgradeDomain
        )
      }
    }
  // check scan for other contracts' transfer:
  // TODO (#5842) check svc governance contracts
  // TODO (#5842) check coinrules contracts
  // TODO (#5957) check coin contracts signed only by SVC (in particular mining rounds)
  // TODO (#5958) check coin and wallet contracts
  // TODO (#5959) check directory contracts
  }
}
