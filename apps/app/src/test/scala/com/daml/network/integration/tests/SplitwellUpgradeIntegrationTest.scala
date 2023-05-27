package com.daml.network.integration.tests

import cats.syntax.either.*
import com.daml.network.codegen.java.cn.{splitwell as splitwellCodegen}
import com.daml.network.codegen.java.cn.wallet.payment as walletCodegen
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.console.SplitwellAppClientReference
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import CNNodeTests.BracketSynchronous.*
import com.daml.network.util.{MultiDomainTestUtil, SplitwellTestUtil, WalletTestUtil}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.protocol.LfContractId
import com.digitalasset.canton.topology.PartyId

import org.slf4j.event.Level
import scala.util.Try

class SplitwellUpgradeIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with MultiDomainTestUtil
    with SplitwellTestUtil
    with WalletTestUtil {

  private val darPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => CNNodeConfigTransforms.useSplitwellUpgradeDomain()(config))
      .withAdditionalSetup(implicit env => {
        aliceValidator.participantClient.dars.upload(darPath)
        bobValidator.participantClient.dars.upload(darPath)
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
        connectSplitwellUpgradeDomain(aliceValidator.participantClient),
        disconnectSplitwellUpgradeDomain(aliceValidator.participantClient),
      ) {
        actAndCheck("alice creates install requests", createInstalls(aliceSplitwell))(
          "alice sees one install contracts",
          _ => {
            val (contracts, newInstall) = twoInstalls(alice, install)
            val contractDomains =
              aliceValidator.participantClient.transfer.lookup_contract_domain(
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
        providerSplitwellBackend.participantClient.transfer.lookup_contract_domain(
          group.contract.contractId,
          invite.contract.contractId,
          acceptedInvite,
        )
      contractDomains shouldBe Seq[LfContractId](
        group.contract.contractId,
        invite.contract.contractId,
        acceptedInvite,
      ).map(cid => cid -> splitwellAlias.unwrap).toMap
      bracket(
        connectSplitwellUpgradeDomain(aliceValidator.participantClient),
        disconnectSplitwellUpgradeDomain(aliceValidator.participantClient),
      ) {
        bracket(
          connectSplitwellUpgradeDomain(bobValidator.participantClient),
          disconnectSplitwellUpgradeDomain(bobValidator.participantClient),
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
                providerSplitwellBackend.participantClient.transfer.lookup_contract_domain(
                  group.contract.contractId,
                  invite.contract.contractId,
                  acceptedInvite,
                )
              contractDomains shouldBe Seq[LfContractId](
                group.contract.contractId,
                invite.contract.contractId,
                acceptedInvite,
              ).map(cid => cid -> splitwellUpgradeAlias.unwrap).toMap
            },
          )
        }
      }
    }

    val globalAlias = DomainAlias tryCreate "global"

    "fully upgrade an active model" in { implicit env =>
      val (alice, _) = clue("Setup some users on the old domain") {
        val alice = onboardWalletUser(aliceWallet, aliceValidator)
        val bob = onboardWalletUser(bobWallet, bobValidator)
        createSplitwellInstalls(aliceSplitwell, alice)
        createSplitwellInstalls(bobSplitwell, bob)
        (alice, bob)
      }

      val abGroupName = "group1"
      import com.daml.network.splitwell.admin.api.client.commands.GrpcSplitwellAppClient.GroupKey
      val abGroupKey = GroupKey(
        alice,
        aliceSplitwell.getProviderPartyId(),
        abGroupName,
      )
      val aGroupName = "group2"
      val aGroupKey = GroupKey(
        alice,
        aliceSplitwell.getProviderPartyId(),
        aGroupName,
      )

      val (abGroup, aGroup, bGroup, abBalanceUpdate, aBalanceUpdate, bobPyReq) = clue(
        "create groups, balance updates, invites, accepted invites"
      ) {
        val bGroupName = "group3"
        val (_, ((abGroup, aGroup), bGroup)) =
          actAndCheck(
            "create groups", {
              aliceSplitwell.requestGroup(abGroupName)
              aliceSplitwell.requestGroup(aGroupName)
              bobSplitwell.requestGroup(bGroupName)
            },
          )(
            "Alice and Bob see their own groups",
            _ =>
              (
                inside(
                  aliceSplitwell
                    .listGroups()
                    .groupMapReduce(_.contract.payload.id.unpack)(identity)((_, b) => b)
                ) {
                  case groups if groups.sizeIs == 2 =>
                    (
                      groups.get(abGroupName).value,
                      groups.get(aGroupName).value,
                    )
                },
                inside(bobSplitwell.listGroups()) { case Seq(bGroup) =>
                  bGroup
                },
              ),
          )

        val invite = clue(s"alice invites bob to $abGroupName, and bob accepts") {
          aliceSplitwell.createGroupInvite(abGroupName)
        }
        val (acceptedInvite, _) =
          actAndCheck(s"bob accepts invite to $abGroupName", bobSplitwell.acceptInvite(invite))(
            "bob join is accepted",
            acceptedInviteId =>
              inside(aliceSplitwell.listAcceptedGroupInvites(abGroupName)) {
                case Seq(seenInvite) if seenInvite.contractId == acceptedInviteId =>
                  ()
              },
          )

        // these are about to be archived
        assertAllOn(splitwellAlias)(
          abGroup.contract.contractId,
          acceptedInvite,
        )
        val (_, newAbGroup) =
          actAndCheck("bob join is finalized", aliceSplitwell.joinGroup(acceptedInvite))(
            s"new $abGroupName is ingested",
            newGroupId =>
              aliceSplitwell.listGroups().find(_.contract.contractId == newGroupId).value,
          )
        val abBalanceUpdate = clue("Alice enters a payment") {
          aliceSplitwell.enterPayment(abGroupKey, BigDecimal("42.42"), "the answer")
        }
        bobWallet.tap(BigDecimal("100"))
        val (bobPyReqId, bobPyReq) = actAndCheck(
          "Bob initiates a transfer",
          bobSplitwell.initiateTransfer(
            abGroupKey,
            Seq(
              new walletCodegen.ReceiverCCAmount(
                alice.toProtoPrimitive,
                BigDecimal("21.20").bigDecimal,
              )
            ),
          ),
        )(
          "bob sees payment request",
          prCid =>
            bobWallet
              .listAppPaymentRequests()
              .collectFirst { case pr if prCid == pr.appPaymentRequest.contractId => pr }
              .value,
        )
        val aBalanceUpdate = clue("Alice enters another payment") {
          aliceSplitwell.enterPayment(aGroupKey, BigDecimal("33.33"), "time left")
        }
        eventually() { assertAllOn(globalAlias)(bobPyReqId, bobPyReq.deliveryOffer.contractId) }
        assertAllOn(splitwellAlias)(
          newAbGroup.contract.contractId,
          aGroup.contract.contractId,
          bGroup.contract.contractId,
          invite.contract.contractId,
          abBalanceUpdate,
          aBalanceUpdate,
        )
        (newAbGroup, aGroup, bGroup, abBalanceUpdate, aBalanceUpdate, bobPyReq)
      }

      // Switch splitwell preferred domain
      bracket(
        connectSplitwellUpgradeDomain(aliceValidator.participantClient),
        disconnectSplitwellUpgradeDomain(aliceValidator.participantClient),
      ) {
        bracket(
          connectSplitwellUpgradeDomain(bobValidator.participantClient),
          disconnectSplitwellUpgradeDomain(bobValidator.participantClient),
        ) {
          clue("Onboard user's participants gradually to new domain") {
            actAndCheck("onboard alice to upgrade", createInstalls(aliceSplitwell))(
              "alice and alice-only group are migrated",
              _ => assertAllOn(splitwellUpgradeAlias)(aGroup.contract.contractId, aBalanceUpdate),
            )
            assertAllOn(splitwellAlias)(
              abGroup.contract.contractId,
              bGroup.contract.contractId,
              abBalanceUpdate,
            )
          }
          val abBalanceUpdate2 = clue("Interleave that with more operations") {
            val abBalanceUpdate2 = clue("Alice enters a third payment") {
              aliceSplitwell.enterPayment(abGroupKey, BigDecimal("42.42"), "the answer")
            }
            val aBalanceUpdate2 = clue("Alice enters a fourth payment") {
              aliceSplitwell.enterPayment(aGroupKey, BigDecimal("33.33"), "time left")
            }
            assertAllOn(splitwellAlias)(abBalanceUpdate2)
            assertAllOn(splitwellUpgradeAlias)(aBalanceUpdate2)
            abBalanceUpdate2
          }
          clue(
            "Test that once all users are migrated we eventually end up with everything being transferred to the new domain"
          ) {
            createInstalls(bobSplitwell)
            eventually() {
              assertAllOn(splitwellUpgradeAlias)(
                abGroup.contract.contractId,
                bGroup.contract.contractId,
                abBalanceUpdate,
                abBalanceUpdate2,
              )
            }

            actAndCheck(
              "Alice accepts the payment after migration",
              bobWallet.acceptAppPaymentRequest(bobPyReq.appPaymentRequest.contractId),
            )(
              "balance updated on the upgrade domain",
              _ =>
                inside(bobSplitwell.listBalanceUpdates(abGroupKey)) { case ups @ Seq(_, _, _) =>
                  assertAllOn(splitwellUpgradeAlias)(ups.map(_.contractId) *)
                },
            )
          }
        }
      }
    }
  }
}
