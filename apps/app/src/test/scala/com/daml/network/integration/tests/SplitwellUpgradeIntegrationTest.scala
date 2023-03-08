package com.daml.network.integration.tests

import cats.syntax.either.*
import com.daml.network.codegen.java.cn.{splitwell as splitwellCodegen}
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTestWithSharedEnvironment,
  CoinTestConsoleEnvironment,
}
import CoinTests.BracketSynchronous.*
import com.daml.network.util.{SplitwellTestUtil, WalletTestUtil}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.sequencing.GrpcSequencerConnection

import org.slf4j.event.Level
import scala.util.Try

class SplitwellUpgradeIntegrationTest
    extends CoinIntegrationTestWithSharedEnvironment
    with SplitwellTestUtil
    with WalletTestUtil {

  private val darPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => CNNodeConfigTransforms.useSplitwellUpgradeDomain()(config))
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(darPath)
      })

  private val splitwellUpgradeAlias = DomainAlias.tryCreate("splitwellUpgrade")
  private val splitwellAlias = DomainAlias.tryCreate("splitwell")

  "splitwell with upgraded domain" should {
    "report both domains" in { implicit env =>
      val splitwellDomains = providerSplitwellBackend.getSplitwellDomainIds()
      splitwellDomains.preferred.uid.id shouldBe "splitwellUpgrade"
      splitwellDomains.others.map(_.uid.id) shouldBe Seq("splitwell")
    }
    "create per domain install contracts" in { implicit env =>
      val alice = onboardWalletUser(aliceWallet, aliceValidator)
      // val splitwellDomains = providerSplitwellBackend.getSplitwellDomainIds()
      val (_, install) =
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

      val upgradeConfig =
        providerSplitwellBackend.remoteParticipant.domains.config(splitwellUpgradeAlias).value

      val url = upgradeConfig.sequencerConnection
        .asInstanceOf[GrpcSequencerConnection]
        .endpoints
        .head
        .toURI(false)
        .toString

      bracket(
        aliceValidator.remoteParticipant.domains.connect(splitwellUpgradeAlias, url),
        aliceValidator.remoteParticipant.domains.disconnect(splitwellUpgradeAlias),
      ) {
        // domains.connect syncs such that the domain shows up in
        // domains.list_connected but does not sync such that the party
        // will be allocated on the domain so we retry until it eventually succeds.
        def createInstalls() =
          eventually() {
            loggerFactory
              .assertLogsSeqWithResult[Try[Unit]](SuppressionRule.LevelAndAbove(Level.WARN))(
                Try(aliceSplitwell.createInstallRequests()),
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
        actAndCheck("alice creates install requests", createInstalls())(
          "alice sees one install contracts",
          _ => {
            val contracts = aliceSplitwell.ledgerApi.ledger_api_extensions.acs
              .filterJava(splitwellCodegen.SplitwellInstall.COMPANION)(alice)
            contracts should have size 2
            contracts should contain(install)
            val newInstall = inside(contracts.filter(_.id != install.id)) { case Seq(c) =>
              c
            }
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
  }
}
