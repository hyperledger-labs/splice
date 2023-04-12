package com.daml.network.integration.tests

import cats.syntax.either.*
import com.daml.network.codegen.java.cn.{splitwell as splitwellCodegen}
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.SplitwellAppClientReference
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import CNNodeTests.BracketSynchronous.*
import com.daml.network.util.{SplitwellTestUtil, WalletTestUtil}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.topology.PartyId

import org.slf4j.event.Level
import scala.util.Try

class SplitwellUpgradeIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with SplitwellTestUtil
    with WalletTestUtil {

  private val darPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => CNNodeConfigTransforms.useSplitwellUpgradeDomain()(config))
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(darPath)
        bobValidator.remoteParticipant.dars.upload(darPath)
      })

  "splitwell with upgraded domain" should {
    "report both domains" in { implicit env =>
      val splitwellDomains = providerSplitwellBackend.getSplitwellDomainIds()
      splitwellDomains.preferred.uid.id shouldBe "splitwellUpgrade"
      splitwellDomains.others.map(_.uid.id) shouldBe Seq("splitwell")
    }

    def installFirstAlice(alice: PartyId)(implicit env: FixtureParam) =
      actAndCheck("alice creates install requests", aliceSplitwell.createInstallRequests())(
        "alice sees one install contracts",
        _ =>
          inside(
            aliceSplitwell.ledgerApi.ledger_api_extensions.acs
              .filterJava(splitwellCodegen.SplitwellInstall.COMPANION)(alice)
              .toList
          ) { case Seq(domain) =>
            domain
          },
      )

    // domains.connect syncs such that the domain shows up in
    // domains.list_connected but does not sync such that the party
    // will be allocated on the domain so we retry until it eventually succeds.
    def createInstalls(splitwells: SplitwellAppClientReference*) = for {
      splitwell <- splitwells
    } eventually() {
      loggerFactory
        .assertLogsSeqWithResult[Try[Unit]](SuppressionRule.LevelAndAbove(Level.WARN))(
          Try(splitwell.createInstallRequests()),
          { case (r, logs) =>
            if (r.isFailure) {
              forExactly(1, logs)(
                _.errorMessage should include(
                  "Not all informee are on the specified domainID: splitwellUpgrade"
                )
              )
            } else {
              logs shouldBe empty
            }
          },
        )
        .toEither
        .valueOr(fail(_))
    }

    def twoInstalls(alice: PartyId, install: splitwellCodegen.SplitwellInstall.Contract)(implicit
        env: FixtureParam
    ) = {
      val contracts = aliceSplitwell.ledgerApi.ledger_api_extensions.acs
        .filterJava(splitwellCodegen.SplitwellInstall.COMPANION)(alice)
      inside(contracts.partition(_.id == install.id)) { case (Seq(`install`), Seq(newInstall)) =>
        (contracts, newInstall)
      }
    }

    "create per domain install contracts" in { implicit env =>
      val alice = onboardWalletUser(aliceWallet, aliceValidator)
      // val splitwellDomains = providerSplitwellBackend.getSplitwellDomainIds()
      val (_, install) = installFirstAlice(alice)

      bracket(
        connectSplitwellUpgradeDomain(aliceValidator.remoteParticipant),
        disconnectSplitwellUpgradeDomain(aliceValidator.remoteParticipant),
      ) {
        actAndCheck("alice creates install requests", createInstalls(aliceSplitwell))(
          "alice sees one install contracts",
          _ => {
            val (contracts, newInstall) = twoInstalls(alice, install)
            val contractDomains =
              aliceValidator.remoteParticipant.transfer.lookup_contract_domain(
                contracts.map[LfContractId](_.id): _*
              )
            contractDomains shouldBe Map[LfContractId, DomainAlias](
              javaToScalaContractId(newInstall.id) -> splitwellUpgradeAlias,
              javaToScalaContractId(install.id) -> splitwellAlias,
            )
          },
        )
      }
    }

    "balance update and invite contracts follow group, which follows installs" in { implicit env =>
      val alice = onboardWalletUser(aliceWallet, aliceValidator)
      val bob = onboardWalletUser(bobWallet, bobValidator)
      createSplitwellInstalls(aliceSplitwell, alice)
      createSplitwellInstalls(bobSplitwell, bob)
      val (_, group) = actAndCheck("create 'group1'", aliceSplitwell.requestGroup("group1"))(
        "Alice sees 'group1'",
        _ =>
          inside(aliceSplitwell.listGroups()) { case Seq(group) =>
            group
          },
      )
      val invite = aliceSplitwell.createGroupInvite(
        "group1"
      )
      val acceptedInvite = bobSplitwell.acceptInvite(invite)
      val contractDomains =
        providerSplitwellBackend.remoteParticipant.transfer.lookup_contract_domain(
          group.contractId,
          invite.contractId,
          acceptedInvite,
        )
      contractDomains shouldBe Seq[LfContractId](
        group.contractId,
        invite.contractId,
        acceptedInvite,
      ).map(cid => cid -> splitwellAlias.unwrap).toMap
      bracket(
        connectSplitwellUpgradeDomain(aliceValidator.remoteParticipant),
        disconnectSplitwellUpgradeDomain(aliceValidator.remoteParticipant),
      ) {
        bracket(
          connectSplitwellUpgradeDomain(bobValidator.remoteParticipant),
          disconnectSplitwellUpgradeDomain(bobValidator.remoteParticipant),
        ) {
          actAndCheck(
            "new installs for alice and bob",
            createInstalls(aliceSplitwell, bobSplitwell),
          )(
            "group, balance update, and invite contracts all follow",
            { _ =>
              // group is transferred out by UpgradeGroupTrigger,
              // and in by the TransferInTrigger.
              val contractDomains =
                providerSplitwellBackend.remoteParticipant.transfer.lookup_contract_domain(
                  group.contractId,
                  invite.contractId,
                  acceptedInvite,
                )
              contractDomains shouldBe Seq[LfContractId](
                group.contractId,
                invite.contractId,
                acceptedInvite,
              ).map(cid => cid -> splitwellUpgradeAlias.unwrap).toMap
            },
          )
        }
      }
    }
  }
}
