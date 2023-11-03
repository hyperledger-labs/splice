package com.daml.network.integration.tests

import com.daml.network.LocalAuth0Test
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{
  DirectoryTestUtil,
  FrontendLoginUtil,
  SplitwellFrontendTestUtil,
  WalletTestUtil,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId

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

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        CNNodeEnvironmentDefinition
          .simpleTopology(this.getClass.getSimpleName)
          .addConfigTransforms(CNNodeConfigTransforms.onlySv1)
          .setup(env)

        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })
      // TODO(#8300) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled

  "A splitwell UI" should {

    "settle debts with multiple parties" in { implicit env =>
      val aliceDamlUser = aliceSplitwellClient.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceParty = aliceUserParty.toProtoPrimitive
      val bobDamlUser = bobSplitwellClient.config.ledgerApiUser
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val bobParty = bobUserParty.toProtoPrimitive
      val charlieDamlUser = charlieSplitwellClient.config.ledgerApiUser
      // we re-use alice's validator here to save some resources
      val charlieValidator = aliceValidatorBackend
      val charlieUserParty = onboardWalletUser(charlieWalletClient, charlieValidator)
      val charlieParty = charlieUserParty.toProtoPrimitive
      val groupName = "troika"

      bobWalletClient.tap(550)

      val invite = withFrontEnd("aliceSplitwell") { implicit webDriver =>
        login(aliceSplitwellUIPort, aliceDamlUser)
        createGroupAndInviteLink(groupName)
      }

      withFrontEnd("bobSplitwell") { implicit webDriver =>
        login(bobSplitwellUIPort, bobDamlUser)
        requestGroupMembership(invite)
      }

      withFrontEnd("aliceSplitwell") { implicit webDriver =>
        eventuallyClickOn(className("add-user-link"))
      }

      withFrontEnd("charlieSplitwell") { implicit webDriver =>
        login(charlieSplitwellUIPort, charlieDamlUser)
        requestGroupMembership(invite)
      }

      withFrontEnd("aliceSplitwell") { implicit webDriver =>
        eventuallyClickOn(className("add-user-link"))

        inside(find(className("enter-payment-amount-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "1200.0"
        }
        inside(find(className("enter-payment-description-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "Team lunch"
        }
        eventuallyClickOn(className("enter-payment-link"))
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
        eventuallyClickOn(className("enter-payment-link"))
      }

      withFrontEnd("bobSplitwell") { implicit webDriver =>
        eventually() {
          inside(findAll(className("balances-table-row")).toSeq) {
            case Seq(r1, r2) => // Need to sync here on the actual values (size not enough)
              matchRow(
                Seq("party-id", "balances-table-amount"),
                Seq(aliceParty, "-400.0000000000"),
              )(r1)

              matchRow(
                Seq("party-id", "balances-table-amount"),
                Seq(charlieParty, "-111.0000000000"),
              )(r2)
          }
        }
        eventuallyClickOn(className("settle-my-debts-link"))

        // Bob is redirected to wallet ..
        loginOnCurrentPage(3001, bobDamlUser)

        eventuallyClickOn(className("payment-accept"))

        // And then back to splitwell, where he is already logged in
        eventually() {
          inside(findAll(className("balances-table-row")).toSeq) { case Seq(row1, row2) =>
            matchRow(
              Seq("party-id", "balances-table-amount"),
              Seq(aliceParty, "0.0000000000"),
            )(row1)

            matchRow(
              Seq("party-id", "balances-table-amount"),
              Seq(charlieParty, "0.0000000000"),
            )(row2)
          }
          val rows = findAll(className("balance-updates-list-item")).toSeq
          rows should have size 4
          // We don't guarantee an order on ACS requests atm so we assert independent of the specific order.
          forExactly(1, rows)(row =>
            matchRow(
              Seq("sender", "description", "receiver"),
              Seq(bobParty, "sent 111.0 CC to", charlieParty),
            )(row)
          )
          forExactly(1, rows)(row =>
            matchRow(
              Seq("sender", "description", "receiver"),
              Seq(bobParty, "sent 400.0 CC to", aliceParty),
            )(row)
          )
          forExactly(1, rows)(row =>
            matchRow(
              Seq("sender", "description"),
              Seq(charlieParty, "paid 333.0 CC for Digestivs"),
            )(row)
          )
          forExactly(1, rows)(row =>
            matchRow(
              Seq("sender", "description"),
              Seq(aliceParty, "paid 1200.0 CC for Team lunch"),
            )(row)
          )
        }
      }

      eventually() {
        // Check final amounts in the wallets
        checkWallet(aliceUserParty, aliceWalletClient, Seq((399.75, 400)))
        checkWallet(bobUserParty, bobWalletClient, Seq((36.3, 36.7)))
        checkWallet(charlieUserParty, charlieWalletClient, Seq((110.75, 111)))
      }
    }

    "settle debts with a single party" in { implicit env =>
      val aliceDamlUser = aliceSplitwellClient.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobDamlUser = bobSplitwellClient.config.ledgerApiUser
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val groupName = "troika"

      val aliceEntryName = perTestCaseName("alice")
      val bobEntryName = perTestCaseName("bob")
      initialiseDirectoryApp(
        aliceEntryName,
        aliceUserParty,
        aliceDirectoryClient,
        aliceWalletClient,
      )
      initialiseDirectoryApp(bobEntryName, bobUserParty, bobDirectoryClient, bobWalletClient)
      val aliceCns = expectedCns(aliceUserParty, aliceEntryName)
      val bobCns = expectedCns(bobUserParty, bobEntryName)
      bobWalletClient.tap(510)

      val invite = withFrontEnd("aliceSplitwell") { implicit webDriver =>
        login(aliceSplitwellUIPort, aliceDamlUser)
        createGroupAndInviteLink(groupName)
      }

      withFrontEnd("bobSplitwell") { implicit webDriver =>
        login(bobSplitwellUIPort, bobDamlUser)
        requestGroupMembership(invite)
      }

      withFrontEnd("aliceSplitwell") { implicit webDriver =>
        eventuallyClickOn(className("add-user-link"))
        addTeamLunch(1000)
      }

      withFrontEnd("bobSplitwell") { implicit webDriver =>
        enterSplitwellPayment(aliceEntryName, aliceUserParty, 500)

        // Bob is redirected to wallet ..
        loginOnCurrentPage(bobWalletUIPort, bobDamlUser)

        eventuallyClickOn(className("payment-accept"))

        // And then back to splitwell, where he is already logged in
        eventually(scaled(5 seconds)) {
          inside(findAll(className("balances-table-row")).toSeq) { case Seq(row) =>
            matchRow(
              Seq("directory-entry", "balances-table-amount"),
              Seq(aliceCns, "0.0000000000"),
            )(row)
          }
          inside(findAll(className("balance-updates-list-item")).toSeq.sortBy(_.text)) {
            case Seq(row1, row2) =>
              matchRow(
                Seq("directory-entry", "description"),
                Seq(aliceCns, "paid 1000.0 CC for Team lunch"),
              )(row1)

              matchRow(
                Seq("sender", "description", "receiver"),
                Seq(bobCns, "sent 500.0 CC to", aliceCns),
              )(row2)
          }
        }
      }

      eventually() {
        // Check final amounts in the wallets
        checkWallet(aliceUserParty, aliceWalletClient, Seq((503.75, 504)))
        checkWallet(bobUserParty, bobWalletClient, Seq((12.3, 12.5)))
      }

      withFrontEnd("bobSplitwell") { implicit webDriver =>
        val errorMsg = "is not part of the group"
        loggerFactory.assertLoggedWarningsAndErrorsSeq(
          enterSplitwellPayment("unknown::abc", PartyId.tryFromProtoPrimitive("unknown::abc"), 42),
          logs => forExactly(1, logs)(_.errorMessage should include(errorMsg)),
        )
        consumeError(errorMsg)
      }
    }

    "handle multiple groups correctly" in { implicit env =>
      val aliceDamlUser = aliceSplitwellClient.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobDamlUser = bobSplitwellClient.config.ledgerApiUser
      onboardWalletUser(bobWalletClient, bobValidatorBackend)
      val charlieDamlUser = charlieSplitwellClient.config.ledgerApiUser
      // we re-use alice's validator here to save some resources
      val charlieValidator = aliceValidatorBackend
      onboardWalletUser(charlieWalletClient, charlieValidator)

      // Alice creates three groups - abc, ab, ac
      val (invite1, invite2, invite3) = withFrontEnd("aliceSplitwell") { implicit webDriver =>
        login(aliceSplitwellUIPort, aliceDamlUser)

        val invite1 = createGroupAndInviteLink("group-abc")
        val invite2 = createGroupAndInviteLink("group-ab")
        val invite3 = createGroupAndInviteLink("group-ac")

        (invite1, invite2, invite3)
      }

      // Bob requests to join groups abc and ab
      withFrontEnd("bobSplitwell") { implicit webDriver =>
        login(bobSplitwellUIPort, bobDamlUser)
        requestGroupMembership(invite1)
        requestGroupMembership(invite2)
      }

      // Charlie requests to join groups abc and ac
      withFrontEnd("charlieSplitwell") { implicit webDriver =>
        login(charlieSplitwellUIPort, charlieDamlUser)
        requestGroupMembership(invite1)
        requestGroupMembership(invite3)
      }

      // Alice accepts all requests
      withFrontEnd("aliceSplitwell") { implicit webDriver =>
        eventually(timeUntilSuccess = 20.minute) {
          findAll(className("add-user-link")) should have length 4
          getGroupContractIds() should have size 3
        }
        val allLinks = findAll(className("add-user-link")).toSeq
        (allLinks zip (4L to 1 by -1)).foreach { case (elem, i) =>
          val groupsBefore = getGroupContractIds()
          groupsBefore should have size 3
          click on elem
          // Wait for the join to finish. Otherwise we get contention on the group contract.
          eventually() {
            findAll(className("add-user-link")) should have length i - 1
            // Wait for the contract id to change.
            val groupsAfter = getGroupContractIds()
            groupsAfter should have size 3
            groupsAfter should not equal groupsBefore
          }
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
      withAuth0LoginCheck("aliceSplitwell", aliceSplitwellUIPort)((_, _, _) => ())
    }
  }
}
