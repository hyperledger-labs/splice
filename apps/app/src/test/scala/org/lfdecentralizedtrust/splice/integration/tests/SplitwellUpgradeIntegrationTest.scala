package org.lfdecentralizedtrust.splice.integration.tests

import cats.syntax.either.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.{splitwell as splitwellCodegen}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as walletCodegen
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.SplitwellAppClientReference
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import SpliceTests.BracketSynchronous.*
import org.lfdecentralizedtrust.splice.util.{MultiDomainTestUtil, SplitwellTestUtil, WalletTestUtil}
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.{SynchronizerId, PartyId}

import org.slf4j.event.Level
import scala.concurrent.duration.DurationInt
import scala.util.Try

class SplitwellUpgradeIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with MultiDomainTestUtil
    with SplitwellTestUtil
    with WalletTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => ConfigTransforms.useSplitwellUpgradeDomain()(config))
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })
      // TODO(#979) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()

  "splitwell with upgraded domain" should {
    "report both domains" in { implicit env =>
      val splitwellDomains = splitwellBackend.getSplitwellSynchronizerIds()
      splitwellDomains.preferred.uid.identifier shouldBe "splitwellUpgrade"
      splitwellDomains.others.map(_.uid.identifier) shouldBe Seq("splitwell")
    }

    def installFirstAlice(alice: PartyId)(implicit env: FixtureParam) =
      actAndCheck("alice creates install requests", aliceSplitwellClient.createInstallRequests())(
        "alice sees one install contracts",
        _ =>
          inside(
            aliceSplitwellClient.ledgerApi.ledger_api_extensions.acs
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
      val contracts = aliceSplitwellClient.ledgerApi.ledger_api_extensions.acs
        .filterJava(splitwellCodegen.SplitwellInstall.COMPANION)(alice)
      inside(contracts.partition(_.id == install.id)) { case (Seq(`install`), Seq(newInstall)) =>
        (contracts, newInstall)
      }
    }

    "create per domain install contracts" in { implicit env =>
      val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val (_, install) = installFirstAlice(alice)

      bracket(
        connectSplitwellUpgradeDomain(aliceValidatorBackend.participantClient, alice),
        disconnectSplitwellUpgradeDomain(aliceValidatorBackend.participantClient),
      ) {
        actAndCheck(timeUntilSuccess = 40.seconds)(
          "alice creates install requests",
          createInstalls(aliceSplitwellClient),
        )(
          "alice sees one install contracts",
          _ => {
            val (contracts, newInstall) = twoInstalls(alice, install)
            val contractDomains =
              aliceValidatorBackend.participantClient.ledger_api_extensions.acs
                .lookup_contract_domain(
                  alice,
                  contracts.map(_.id.contractId).toSet,
                )
            val splitwellUpgradeSynchronizerId =
              aliceValidatorBackend.participantClient.synchronizers.id_of(splitwellUpgradeAlias)
            val splitwellSynchronizerId =
              aliceValidatorBackend.participantClient.synchronizers.id_of(splitwellAlias)
            contractDomains should contain theSameElementsAs Map[String, SynchronizerId](
              newInstall.id.contractId -> splitwellUpgradeSynchronizerId,
              install.id.contractId -> splitwellSynchronizerId,
            )
          },
        )
      }
    }

    "balance update and invite contracts follow group, which follows installs" in { implicit env =>
      val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bob = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      createSplitwellInstalls(aliceSplitwellClient, alice)
      createSplitwellInstalls(bobSplitwellClient, bob)
      val (_, group) = actAndCheck("create 'group1'", aliceSplitwellClient.requestGroup("group1"))(
        "Alice sees 'group1'",
        _ =>
          inside(aliceSplitwellClient.listGroups()) { case Seq(group) =>
            group
          },
      )
      val (_, invite) = actAndCheck(
        "Alice creates invite",
        aliceSplitwellClient.createGroupInvite(
          "group1"
        ),
      )(
        "alice observes the invite",
        _ => aliceSplitwellClient.listGroupInvites().loneElement.toAssignedContract.value,
      )
      val acceptedInvite = bobSplitwellClient.acceptInvite(invite)
      val splitwellSynchronizerId =
        aliceValidatorBackend.participantClient.synchronizers.id_of(splitwellAlias).logical
      val splitwellUpgradeSynchronizerId =
        aliceValidatorBackend.participantClient.synchronizers.id_of(splitwellUpgradeAlias).logical

      eventually() {
        val contractDomains =
          splitwellBackend.participantClient.ledger_api_extensions.acs.lookup_contract_domain(
            splitwellBackend.getProviderPartyId(),
            Set(
              group.contract.contractId.contractId,
              invite.contract.contractId.contractId,
              acceptedInvite.contractId,
            ),
          )
        contractDomains shouldBe Seq[String](
          group.contract.contractId.contractId,
          invite.contract.contractId.contractId,
          acceptedInvite.contractId,
        ).map(cid => cid -> splitwellSynchronizerId).toMap
      }
      bracket(
        connectSplitwellUpgradeDomain(aliceValidatorBackend.participantClient, alice),
        disconnectSplitwellUpgradeDomain(aliceValidatorBackend.participantClient),
      ) {
        bracket(
          connectSplitwellUpgradeDomain(bobValidatorBackend.participantClient, bob),
          disconnectSplitwellUpgradeDomain(bobValidatorBackend.participantClient),
        ) {
          actAndCheck(
            "new installs for alice and bob",
            createInstalls(aliceSplitwellClient, bobSplitwellClient),
          )(
            "group, balance update, and invite contracts all follow",
            { _ =>
              // group is transferred out by UpgradeGroupTrigger,
              // and in by the AssignTrigger.
              val contractDomains =
                splitwellBackend.participantClient.ledger_api_extensions.acs.lookup_contract_domain(
                  splitwellBackend.getProviderPartyId(),
                  Set(
                    group.contract.contractId.contractId,
                    invite.contract.contractId.contractId,
                    acceptedInvite.contractId,
                  ),
                )
              contractDomains shouldBe Seq[String](
                group.contract.contractId.contractId,
                invite.contract.contractId.contractId,
                acceptedInvite.contractId,
              ).map(cid => cid -> splitwellUpgradeSynchronizerId).toMap
            },
          )
        }
      }
    }

    "fully upgrade an active model" in { implicit env =>
      val (alice, bob) = clue("Setup some users on the old domain") {
        val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val bob = onboardWalletUser(bobWalletClient, bobValidatorBackend)
        createSplitwellInstalls(aliceSplitwellClient, alice)
        createSplitwellInstalls(bobSplitwellClient, bob)
        (alice, bob)
      }

      val abGroupName = "group1"
      import org.lfdecentralizedtrust.splice.splitwell.admin.api.client.commands.HttpSplitwellAppClient.GroupKey
      val abGroupKey = GroupKey(
        abGroupName,
        alice,
      )
      val aGroupName = "group2"
      val aGroupKey = GroupKey(
        aGroupName,
        alice,
      )

      val (abGroup, aGroup, bGroup, abBalanceUpdate, aBalanceUpdate, bobPyReq) = clue(
        "create groups, balance updates, invites, accepted invites"
      ) {
        val bGroupName = "group3"
        val (_, ((abGroup, aGroup), bGroup)) =
          actAndCheck(
            "create groups", {
              aliceSplitwellClient.requestGroup(abGroupName)
              aliceSplitwellClient.requestGroup(aGroupName)
              bobSplitwellClient.requestGroup(bGroupName)
            },
          )(
            "Alice and Bob see their own groups",
            _ =>
              (
                inside(
                  aliceSplitwellClient
                    .listGroups()
                    .groupMapReduce(_.contract.payload.id.unpack)(identity)((_, b) => b)
                ) {
                  case groups if groups.sizeIs == 2 =>
                    (
                      groups.get(abGroupName).value,
                      groups.get(aGroupName).value,
                    )
                },
                inside(bobSplitwellClient.listGroups()) { case Seq(bGroup) =>
                  bGroup
                },
              ),
          )

        val (_, invite) = actAndCheck(
          "Alice creates invite",
          aliceSplitwellClient.createGroupInvite(
            abGroupName
          ),
        )(
          "alice observes the invite",
          _ => aliceSplitwellClient.listGroupInvites().loneElement.toAssignedContract.value,
        )
        val (acceptedInvite, _) =
          actAndCheck(
            s"bob accepts invite to $abGroupName",
            bobSplitwellClient.acceptInvite(invite),
          )(
            "bob join is accepted",
            acceptedInviteId =>
              inside(aliceSplitwellClient.listAcceptedGroupInvites(abGroupName)) {
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
          actAndCheck("bob join is finalized", aliceSplitwellClient.joinGroup(acceptedInvite))(
            s"new $abGroupName is ingested",
            newGroupId =>
              aliceSplitwellClient.listGroups().find(_.contract.contractId == newGroupId).value,
          )
        val abBalanceUpdate = clue("Alice enters a payment") {
          aliceSplitwellClient.enterPayment(abGroupKey, BigDecimal("42.42"), "the answer")
        }
        bobWalletClient.tap(BigDecimal("100"))
        val (bobPyReqId, bobPyReq) = actAndCheck(
          "Bob initiates a transfer",
          bobSplitwellClient.initiateTransfer(
            abGroupKey,
            Seq(
              new walletCodegen.ReceiverAmuletAmount(
                alice.toProtoPrimitive,
                BigDecimal("21.20").bigDecimal,
              )
            ),
          ),
        )(
          "bob sees payment request",
          prCid =>
            bobWalletClient
              .listAppPaymentRequests()
              .collectFirst { case pr if prCid == pr.contractId => pr }
              .value,
        )
        val aBalanceUpdate = clue("Alice enters another payment") {
          aliceSplitwellClient.enterPayment(aGroupKey, BigDecimal("33.33"), "time left")
        }
        eventually() {
          assertAllOn(splitwellAlias)(
            newAbGroup.contract.contractId,
            aGroup.contract.contractId,
            bGroup.contract.contractId,
            invite.contract.contractId,
            abBalanceUpdate,
            aBalanceUpdate,
            // Only gets reassigned on usage so still on splitwell domain
            bobPyReqId,
          )
        }
        (newAbGroup, aGroup, bGroup, abBalanceUpdate, aBalanceUpdate, bobPyReq)
      }

      // Switch splitwell preferred domain
      bracket(
        connectSplitwellUpgradeDomain(aliceValidatorBackend.participantClient, alice),
        disconnectSplitwellUpgradeDomain(aliceValidatorBackend.participantClient),
      ) {
        bracket(
          connectSplitwellUpgradeDomain(bobValidatorBackend.participantClient, bob),
          disconnectSplitwellUpgradeDomain(bobValidatorBackend.participantClient),
        ) {
          clue("Onboard user's participants gradually to new domain") {
            actAndCheck("onboard alice to upgrade", createInstalls(aliceSplitwellClient))(
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
              aliceSplitwellClient.enterPayment(abGroupKey, BigDecimal("42.42"), "the answer")
            }
            val aBalanceUpdate2 = clue("Alice enters a fourth payment") {
              aliceSplitwellClient.enterPayment(aGroupKey, BigDecimal("33.33"), "time left")
            }
            assertAllOn(splitwellAlias)(abBalanceUpdate2)
            assertAllOn(splitwellUpgradeAlias)(aBalanceUpdate2)
            abBalanceUpdate2
          }
          clue(
            "Test that once all users are migrated we eventually end up with everything being transferred to the new domain"
          ) {
            createInstalls(bobSplitwellClient)
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
              bobWalletClient.acceptAppPaymentRequest(bobPyReq.contractId),
            )(
              "balance updated on the upgrade domain",
              _ =>
                inside(bobSplitwellClient.listBalanceUpdates(abGroupKey)) {
                  case ups @ Seq(_, _, _) =>
                    assertAllOn(splitwellUpgradeAlias)(ups.map(_.contractId)*)
                },
            )
          }
        }
      }
    }
  }
}
