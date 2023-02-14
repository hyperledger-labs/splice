package com.daml.network.integration.tests

import com.daml.network.LocalAuth0Test
import com.daml.network.codegen.java.cn.splitwell as splitwellCodegen
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.util.{
  DirectoryTestUtil,
  FrontendLoginUtil,
  SplitwellFrontendTestUtil,
  WalletTestUtil,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.concurrent.duration.DurationInt

class SplitwellFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment(
      "aliceSplitwell",
      "bobSplitwell",
      "charlieSplitwell",
    )
    with DirectoryTestUtil
    with WalletTestUtil
    with SplitwellFrontendTestUtil
    with FrontendLoginUtil {

  private val splitwellDarPath = "daml/splitwell/.daml/dist/splitwell-0.1.0.dar"
  private val directoryDarPath = "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        CoinEnvironmentDefinition.simpleTopology(this.getClass.getSimpleName).setup(env)
        Seq(splitwellDarPath, directoryDarPath).foreach { path =>
          aliceValidator.remoteParticipant.dars.upload(path)
          bobValidator.remoteParticipant.dars.upload(path)
        }
      })

  "A splitwell UI" should {

    "settle debts with multiple parties" in { implicit env =>
      val aliceDamlUser = aliceSplitwell.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val bobDamlUser = bobSplitwell.config.ledgerApiUser
      val bobUserParty = onboardWalletUser(bobWallet, bobValidator)
      val charlieDamlUser = charlieSplitwell.config.ledgerApiUser
      // we re-use alice's validator here to save some resources
      val charlieValidator = aliceValidator
      val charlieUserParty = onboardWalletUser(charlieWallet, charlieValidator)
      val groupName = "troika"

      val aliceEntryName = perTestCaseName("alice.cns")
      val bobEntryName = perTestCaseName("bob.cns")
      val charlieEntryName = perTestCaseName("charlie.cns")
      initialiseDirectoryApp(aliceEntryName, aliceUserParty, aliceDirectory, aliceWallet)
      initialiseDirectoryApp(bobEntryName, bobUserParty, bobDirectory, bobWallet)
      initialiseDirectoryApp(charlieEntryName, charlieUserParty, charlieDirectory, charlieWallet)
      val aliceCns = expectedCns(aliceUserParty, aliceEntryName)
      val bobCns = expectedCns(bobUserParty, bobEntryName)
      val charlieCns = expectedCns(charlieUserParty, charlieEntryName)

      bobWallet.tap(550)

      withFrontEnd("aliceSplitwell") { implicit webDriver =>
        login(3002, aliceDamlUser)
        createGroupAndInviteLink(groupName)
      }

      withFrontEnd("bobSplitwell") { implicit webDriver =>
        login(3003, bobDamlUser)
        bobValidator.remoteParticipantWithAdminToken.ledger_api.acs
          .awaitJava(splitwellCodegen.GroupInvite.COMPANION)(bobUserParty)
        click on className("request-membership-link")
      }

      withFrontEnd("aliceSplitwell") { implicit webDriver =>
        click on className("add-user-link")
      }

      withFrontEnd("charlieSplitwell") { implicit webDriver =>
        login(3005, charlieDamlUser)
        charlieValidator.remoteParticipantWithAdminToken.ledger_api.acs
          .awaitJava(splitwellCodegen.GroupInvite.COMPANION)(charlieUserParty)
        click on className("request-membership-link")
      }

      withFrontEnd("aliceSplitwell") { implicit webDriver =>
        click on className("add-user-link")

        inside(find(className("enter-payment-amount-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "1200.0"
        }
        inside(find(className("enter-payment-description-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "Team lunch"
        }
        click on className("enter-payment-link")
      }

      withFrontEnd("charlieSplitwell") { implicit webDriver =>
        inside(find(className("enter-payment-amount-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "333.0"
        }
        inside(find(className("enter-payment-description-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "Digestivs"
        }
        click on className("enter-payment-link")
      }

      withFrontEnd("bobSplitwell") { implicit webDriver =>
        eventually() {
          inside(findAll(className("balances-table-row")).toSeq) {
            case Seq(r1, r2) => // Need to sync here on the actual values (size not enough)
              r1.childElement(className("balances-table-receiver")).text should matchText(
                aliceCns
              )
              r1.childElement(className("balances-table-amount")).text shouldBe "-400.0000000000"
              r2.childElement(className("balances-table-receiver")).text should matchText(
                charlieCns
              )
              r2.childElement(className("balances-table-amount")).text shouldBe "-111.0000000000"
          }
        }
        click on className("settle-my-debts-link")

        // Bob is redirected to wallet ..
        loginOnCurrentPage(bobDamlUser)

        click on className("accept-button")

        // And then back to splitwell, where he is already logged in
        eventually() {
          inside(findAll(className("balances-table-row")).toSeq) { case Seq(row1, row2) =>
            row1.childElement(className("balances-table-receiver")).text should matchText(
              aliceCns
            )
            row1.childElement(className("balances-table-amount")).text shouldBe "0.0000000000"
            row2.childElement(className("balances-table-receiver")).text should matchText(
              charlieCns
            )
            row2.childElement(className("balances-table-amount")).text shouldBe "0.0000000000"
          }
          inside(findAll(className("balance-updates-list-item")).toSeq) {
            case Seq(row1, row2, row3, row4) =>
              row1.text should matchText(s"${bobCns} sent 111.0000000000 CC to ${charlieCns}")
              row2.text should matchText(s"${bobCns} sent 400.0000000000 CC to ${aliceCns}")
              row3.text should matchText(s"${charlieCns} paid 333.0000000000 CC for Digestivs")
              row4.text should matchText(s"${aliceCns} paid 1200.0000000000 CC for Team lunch")
          }
        }
      }

      eventually() {
        // Check final amounts in the wallets
        checkWallet(aliceUserParty, aliceWallet, Seq((403.75, 404)))
        checkWallet(bobUserParty, bobWallet, Seq((40.3, 40.5)))
        checkWallet(charlieUserParty, charlieWallet, Seq((114.75, 115)))
      }
    }

    "settle debts with a single party" in { implicit env =>
      val aliceDamlUser = aliceSplitwell.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val bobDamlUser = bobSplitwell.config.ledgerApiUser
      val bobUserParty = onboardWalletUser(bobWallet, bobValidator)
      val groupName = "troika"

      val aliceEntryName = perTestCaseName("alice.cns")
      val bobEntryName = perTestCaseName("bob.cns")
      initialiseDirectoryApp(aliceEntryName, aliceUserParty, aliceDirectory, aliceWallet)
      initialiseDirectoryApp(bobEntryName, bobUserParty, bobDirectory, bobWallet)
      val aliceCns = expectedCns(aliceUserParty, aliceEntryName)
      val bobCns = expectedCns(bobUserParty, bobEntryName)
      bobWallet.tap(510)

      withFrontEnd("aliceSplitwell") { implicit webDriver =>
        login(3002, aliceDamlUser)
        createGroupAndInviteLink(groupName)
      }

      withFrontEnd("bobSplitwell") { implicit webDriver =>
        login(3003, bobDamlUser)
        bobValidator.remoteParticipantWithAdminToken.ledger_api.acs
          .awaitJava(splitwellCodegen.GroupInvite.COMPANION)(bobUserParty)
        click on className("request-membership-link")
      }

      withFrontEnd("aliceSplitwell") { implicit webDriver =>
        click on className("add-user-link")
        addTeamLunch(1000)
      }

      withFrontEnd("bobSplitwell") { implicit webDriver =>
        enterSplitwellPayment(aliceEntryName, 500)

        // Bob is redirected to wallet ..
        loginOnCurrentPage(bobDamlUser)

        click on className("accept-button")

        // And then back to splitwell, where he is already logged in
        eventually(scaled(5 seconds)) {
          inside(findAll(className("balances-table-row")).toSeq) { case Seq(row) =>
            row.childElement(className("balances-table-receiver")).text should matchText(aliceCns)
            row.childElement(className("balances-table-amount")).text.toDouble shouldBe 0.0
          }
          inside(findAll(className("balance-updates-list-item")).toSeq.sortBy(_.text)) {
            case Seq(row1, row2) =>
              row1.text should matchText(s"${aliceCns} paid 1000.0000000000 CC for Team lunch")
              row2.text should matchText(s"${bobCns} sent 500.0000000000 CC to ${aliceCns}")
          }
        }
      }

      eventually() {
        // Check final amounts in the wallets
        checkWallet(aliceUserParty, aliceWallet, Seq((503.75, 504)))
        checkWallet(bobUserParty, bobWallet, Seq((12.3, 12.5)))
      }
    }

    "handle multiple groups correctly" in { implicit env =>
      val aliceDamlUser = aliceSplitwell.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val bobDamlUser = bobSplitwell.config.ledgerApiUser
      val bobUserParty = onboardWalletUser(bobWallet, bobValidator)
      val charlieDamlUser = charlieSplitwell.config.ledgerApiUser
      // we re-use alice's validator here to save some resources
      val charlieValidator = aliceValidator
      val charlieUserParty = onboardWalletUser(charlieWallet, charlieValidator)

      val aliceEntryName = perTestCaseName("alice.cns")
      val bobEntryName = perTestCaseName("bob.cns")
      val charlieEntryName = perTestCaseName("charlie.cns")
      initialiseDirectoryApp(aliceEntryName, aliceUserParty, aliceDirectory, aliceWallet)
      initialiseDirectoryApp(bobEntryName, bobUserParty, bobDirectory, bobWallet)
      initialiseDirectoryApp(charlieEntryName, charlieUserParty, charlieDirectory, charlieWallet)

      // Alice creates three groups - abc, ab, ac
      withFrontEnd("aliceSplitwell") { implicit webDriver =>
        login(3002, aliceDamlUser)

        createGroup("group-abc")
        createGroup("group-ab")
        createGroup("group-ac")

        eventually() {
          findAll(className("create-invite-link")).toSeq should have length 3
        }
        findAll(className("create-invite-link")).toSeq.map(click on _)
      }

      // Bob requests to join groups abc and ab
      withFrontEnd("bobSplitwell") { implicit webDriver =>
        login(3003, bobDamlUser)
        bobValidator.remoteParticipantWithAdminToken.ledger_api.acs
          .awaitJava(splitwellCodegen.GroupInvite.COMPANION)(bobUserParty)
        eventually() {
          findAll(className("request-membership-link")).toSeq should have length 3
        }
        click on findAll(className("request-membership-link")).toSeq
          .filter(_.attribute("data-owner") == Some(aliceUserParty.toProtoPrimitive))
          .filter(_.attribute("data-group") == Some("group-abc"))
          .head
        click on findAll(className("request-membership-link")).toSeq
          .filter(_.attribute("data-owner") == Some(aliceUserParty.toProtoPrimitive))
          .filter(_.attribute("data-group") == Some("group-ab"))
          .head
      }

      // Charlie requests to join groups abc and ac
      withFrontEnd("charlieSplitwell") { implicit webDriver =>
        login(3005, charlieDamlUser)
        bobValidator.remoteParticipantWithAdminToken.ledger_api.acs
          .awaitJava(splitwellCodegen.GroupInvite.COMPANION)(bobUserParty)
        eventually() {
          findAll(className("request-membership-link")).toSeq should have length 3
        }
        click on findAll(className("request-membership-link")).toSeq
          .filter(_.attribute("data-owner") == Some(aliceUserParty.toProtoPrimitive))
          .filter(_.attribute("data-group") == Some("group-abc"))
          .head
        click on findAll(className("request-membership-link")).toSeq
          .filter(_.attribute("data-owner") == Some(aliceUserParty.toProtoPrimitive))
          .filter(_.attribute("data-group") == Some("group-ac"))
          .head
      }

      // Alice accepts all requests
      withFrontEnd("aliceSplitwell") { implicit webDriver =>
        eventually(timeUntilSuccess = 20.minute) {
          findAll(className("add-user-link")) should have length 4
        }
        val allLinks = findAll(className("add-user-link")).toSeq
        (allLinks zip (4L to 1 by -1)).foreach { case (elem, i) =>
          // Wait for the previous join to finish. Otherwise we get contention on the group contract.
          eventually() {
            findAll(className("add-user-link")) should have length i
          }
          click on elem
        }
      }

      withFrontEnd("bobSplitwell") { implicit webDriver =>
        eventually() {
          findAll(className("group-entry")) should have length 2
          val groups = findAll(className("group-entry")).toSeq
            .map(elem => (elem.attribute("data-group-owner"), elem.attribute("data-group-id")))
            .sorted
          groups should be(
            Seq(
              (Some(aliceUserParty.toProtoPrimitive), Some("group-ab")),
              (Some(aliceUserParty.toProtoPrimitive), Some("group-abc")),
            )
          )
        }
      }
      withFrontEnd("charlieSplitwell") { implicit webDriver =>
        eventually() {
          findAll(className("group-entry")) should have length 2
          val groups = findAll(className("group-entry")).toSeq
            .map(elem => (elem.attribute("data-group-owner"), elem.attribute("data-group-id")))
            .sorted
          groups should be(
            Seq(
              (Some(aliceUserParty.toProtoPrimitive), Some("group-abc")),
              (Some(aliceUserParty.toProtoPrimitive), Some("group-ac")),
            )
          )
        }
      }
    }

    "allow login via auth0" taggedAs LocalAuth0Test in { implicit env =>
      withAuth0LoginCheck("aliceSplitwell", 3005)((_, _) => ())
    }
  }
}
