package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.codegen.java.splice.splitwell as splitwellCodegen
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.splitwell.admin.api.client.commands.HttpSplitwellAppClient.GroupKey
import SpliceTests.BracketSynchronous.*
import org.lfdecentralizedtrust.splice.util.{
  FrontendLoginUtil,
  MultiDomainTestUtil,
  SplitwellTestUtil,
  SplitwellFrontendTestUtil,
  WalletTestUtil,
}
import SplitwellUpgradeFrontendIntegrationTest.*
import org.scalatest.Ignore

// TODO(DACH-NY/canton-network-internal#1834) Reenable once we sorted out the reassignment issues
@Ignore
class SplitwellUpgradeFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment(aliceSplitwellFE, bobSplitwellFE)
    with FrontendLoginUtil
    with MultiDomainTestUtil
    with SplitwellTestUtil
    with WalletTestUtil
    with SplitwellFrontendTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => ConfigTransforms.useSplitwellUpgradeDomain()(config))
      .withAdditionalSetup(implicit env => {
        EnvironmentDefinition
          .simpleTopology1Sv(this.getClass.getSimpleName)
          .setup(env)
        for {
          validator <- Seq(aliceValidatorBackend, bobValidatorBackend)
        } validator.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })
      // TODO(#979) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()

  "splitwell frontend with upgraded domain" should {
    "create per domain install contracts" in { implicit env =>
      val (splitwellPreferred, oldSplitwellDomain) = preferredAndPriorDomains

      val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceUser = aliceSplitwellClient.config.ledgerApiUser
      withFrontEnd(aliceSplitwellFE) { implicit webDriver =>
        login(aliceSplitwellUIPort, aliceUser)
      }

      eventually() {
        aliceSplitwellClient.listSplitwellInstalls().keys shouldBe Set(oldSplitwellDomain)
      }

      bracket(
        connectSplitwellUpgradeDomain(aliceValidatorBackend.participantClient, alice),
        disconnectSplitwellUpgradeDomain(aliceValidatorBackend.participantClient),
      ) {
        withFrontEnd(aliceSplitwellFE) { implicit webDriver =>
          reloadPage()
        }
        eventually() {
          aliceSplitwellClient.listSplitwellInstalls().keys shouldBe Set(
            oldSplitwellDomain,
            splitwellPreferred,
          )
        }
        // Wait for all install requests to get rejected. Otherwise, we disconnect the user’s participant too soon and
        // the provider’s backend automation times out on the reject call which can break shutdown.
        clue("Install requests get rejected") {
          eventually() {
            val contracts = splitwellBackend.participantClient.ledger_api_extensions.acs
              .filterJava(splitwellCodegen.SplitwellInstallRequest.COMPANION)(
                splitwellBackend.getProviderPartyId()
              )
            contracts shouldBe empty
          }
        }
      }
    }

    "fully upgrade an active model" in { implicit env =>
      val (alice, bob) = clue("Setup some users on the old domain") {
        val alice = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val bob = onboardWalletUser(bobWalletClient, bobValidatorBackend)
        (alice, bob)
      }
      val aliceDamlUser = aliceSplitwellClient.config.ledgerApiUser
      val bobDamlUser = bobSplitwellClient.config.ledgerApiUser

      val abGroupName = "group1"
      val aGroupName = "group2"
      val bGroupName = "group3"

      def checkSoleBalance(amount: String)(implicit wd: WebDriverType) = eventually() {
        inside(findAll(className("balances-table-row")).toSeq) { case Seq(r1) =>
          matchRow(Seq("party-id", "balances-table-amount"), Seq(alice.toProtoPrimitive, amount))(
            r1
          )
        }
      }

      val bobWalletResume = clue(
        "create groups, balance updates, invites, accepted invites"
      ) {

        val invite = withFrontEnd(aliceSplitwellFE) { implicit webDriver =>
          login(aliceSplitwellUIPort, aliceDamlUser)
          eventually() { userIsLoggedIn() }
          createGroupAndInviteLink(aGroupName) withClue "alice creates unshared group"
          createGroupAndInviteLink(abGroupName) withClue "alice creates group/copies invite"
        }

        withFrontEnd(bobSplitwellFE) { implicit webDriver =>
          login(bobSplitwellUIPort, bobDamlUser)
          eventually() { userIsLoggedIn() }
          createGroupAndInviteLink(bGroupName)
          requestGroupMembership(invite) withClue "bob asks to join alice's group"
        }

        withFrontEnd(aliceSplitwellFE) { implicit webDriver =>
          val groupsBefore = eventually() {
            val groupsBefore = getGroupContractIds()
            groupsBefore should have size 2
            groupsBefore
          }

          clue("Alice sees bob’s accepted invite") {
            eventually() {
              findAll(className("add-user-link")).toSeq should have size 1
            }
          }

          actAndCheck(
            "Alice accepts bob's request",
            click on className("add-user-link") withClue "alice accepts bob's request",
          )(
            "Group contract id changes",
            _ => {
              val groupsAfter = getGroupContractIds()
              groupsAfter should have size 2
              groupsAfter should not equal groupsBefore
            },
          )

          enterPayment(abGroupName, "42.42", "the answer") withClue "Alice enters a payment"
        }

        bobWalletClient.tap(BigDecimal("100"))
        val (_, bobWalletResume) = withFrontEnd(bobSplitwellFE) { implicit webDriver =>
          eventually() {
            checkSoleBalance("-21.2100000000")
          }

          // we want to create the AppPaymentRequest and DeliveryOffer *before*
          // installing on the upgrade domain, but we don't want to complete
          // the workflow
          actAndCheck(
            "bob creates AppPaymentRequest and DeliveryOffer",
            click on cssSelector(
              s""".group-entry[data-group-id="$abGroupName"] ~ * .settle-my-debts-link"""
            ),
          )(
            "Bob was redirected to wallet",
            { _ =>
              val bobWalletResume = currentUrl
              bobWalletResume should startWith(s"http://localhost:3001")
              bobWalletResume
            },
          )
        }

        withFrontEnd(aliceSplitwellFE) { implicit webDriver =>
          enterPayment(aGroupName, "33.33", "time left") withClue "Alice enters another payment"
        }

        bobWalletResume
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
          val (preferred, old) = preferredAndPriorDomains

          // this needs an invite copy button to work, hence the
          // createGroupAndInviteLink above even when we don't use it
          def groupOnPreferred(groupName: String)(implicit wd: WebDriverType) = {
            val refreshedInvite = inside(
              findAll(className("invite-copy-button"))
                .filter(_.attribute("data-group-id") == Some(groupName))
                .toSeq
            ) { case Seq(button) =>
              button.attribute("data-invite-contract").value
            }
            refreshedInvite should include(
              preferred.toProtoPrimitive
            ) withClue s"$groupName has moved to upgraded domain"
          }

          clue("Onboard user's participants gradually to new domain") {
            withFrontEnd(aliceSplitwellFE) { implicit webDriver =>
              actAndCheck("refresh so alice upgrades", reloadPage())(
                "alice installs on new domain",
                _ => aliceSplitwellClient.listSplitwellInstalls().keys shouldBe Set(preferred, old),
              )

              eventually() {
                groupOnPreferred(aGroupName)
              }
            }
          }
          clue("Interleave that with more operations") {
            withFrontEnd(aliceSplitwellFE) { implicit webDriver =>
              enterPayment(
                abGroupName,
                "42.42",
                "the answer",
              ) withClue "Alice enters a third payment"
              enterPayment(
                aGroupName,
                "33.33",
                "time left",
              ) withClue "Alice enters a fourth payment"
            }
          }
          clue(
            "Test that once all users are migrated we eventually end up with everything being transferred to the new domain"
          ) {
            withFrontEnd(bobSplitwellFE) { implicit webDriver =>
              actAndCheck(
                "return from wallet to splitwell",
                go to s"http://localhost:${bobSplitwellUIPort}",
              )(
                "bob is back on splitwell and upgrading",
                { _ =>
                  userIsLoggedIn()
                  bobSplitwellClient.listSplitwellInstalls().keys shouldBe Set(preferred, old)
                  groupOnPreferred(bGroupName)
                },
              )
            }

            withFrontEnd(aliceSplitwellFE) { implicit webDriver =>
              eventually() {
                groupOnPreferred(abGroupName)
              } withClue s"bob's upgrade lets $abGroupName upgrade"
            }

            withFrontEnd(bobSplitwellFE) { implicit webDriver =>
              go to bobWalletResume
              loginOnCurrentPage(bobWalletUIPort, bobDamlUser)
              actAndCheck(
                "Bob completes payment after migration",
                eventuallyClickOn(className("payment-accept")),
              )(
                "Bob returns to splitwell and balance update gets transferred to splitwellUpgrade",
                _ => {
                  currentUrl should startWith(s"http://localhost:${bobSplitwellUIPort}")
                  val balanceUpdates =
                    bobSplitwellClient.listBalanceUpdates(GroupKey(abGroupName, alice))
                  balanceUpdates should have size 3
                  assertAllOn(splitwellUpgradeAlias)(balanceUpdates.map(_.contractId)*)
                },
              )
            }
          }
        }
      }
    }
  }

  private[this] def preferredAndPriorDomains(implicit env: FixtureParam) = {
    val splitwellDomains = splitwellBackend.getSplitwellSynchronizerIds()
    (
      splitwellDomains.preferred,
      inside(splitwellDomains.others) { case Seq(d) =>
        d
      },
    )
  }

  private[this] def enterPayment(groupName: String, amount: String, description: String)(implicit
      wd: WebDriverType
  ) = {
    val groupEntry = s""".group-entry[data-group-id="$groupName"]"""
    inside(
      find(cssSelector(s"$groupEntry .enter-payment-amount-field"))
    ) { case Some(field) =>
      field.underlying.click()
      reactTextInput(field).value = amount
    }
    inside(find(cssSelector(s"$groupEntry .enter-payment-description-field"))) { case Some(field) =>
      field.underlying.click()
      reactTextInput(field).value = description
    }
    click on cssSelector(s"$groupEntry .enter-payment-link")
  }
}

object SplitwellUpgradeFrontendIntegrationTest {
  private val aliceSplitwellFE = "aliceSplitwell"
  private val bobSplitwellFE = "bobSplitwell"
}
