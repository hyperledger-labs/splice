package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{
  AnsEntryTestUtil,
  FrontendLoginUtil,
  SplitwellFrontendTestUtil,
  WalletTestUtil,
}
import com.digitalasset.canton.topology.PartyId

import scala.concurrent.duration.DurationInt

class SplitwellFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment(
      "aliceSplitwell",
      "bobSplitwell",
      "charlieSplitwell",
    )
    with AnsEntryTestUtil
    with WalletTestUtil
    with SplitwellFrontendTestUtil
    with FrontendLoginUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        EnvironmentDefinition
          .simpleTopology1Sv(this.getClass.getSimpleName)
          .setup(env)

        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })
      // TODO(#979) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()

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

      bobWalletClient.tap(walletAmuletToUsd(550))

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
        eventually() {
          findAll(className("balances-table-row")).toSeq should have length 2
        }

        inside(eventuallyFind(className("enter-payment-amount-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "1200.0"
        }
        inside(eventuallyFind(className("enter-payment-description-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "Team lunch"
        }
        eventuallyClickOn(className("enter-payment-link"))
      }

      withFrontEnd("charlieSplitwell") { implicit webDriver =>
        inside(eventuallyFind(className("enter-payment-amount-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "333.0"
        }
        inside(eventuallyFind(className("enter-payment-description-field"))) { case Some(field) =>
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
        }
        clue("Checking Bob’s balance updates") {

          eventually() {
            val rows = findAll(className("balance-updates-list-item")).toSeq
            rows should have size 4
            // We don't guarantee an order on ACS requests atm so we assert independent of the specific order.
            forExactly(1, rows)(row =>
              matchRow(
                Seq("sender", "description", "receiver"),
                Seq(bobParty, s"sent 111.0 $amuletNameAcronym to", charlieParty),
              )(row)
            )
            forExactly(1, rows)(row =>
              matchRow(
                Seq("sender", "description", "receiver"),
                Seq(bobParty, s"sent 400.0 $amuletNameAcronym to", aliceParty),
              )(row)
            )
            forExactly(1, rows)(row =>
              matchRow(
                Seq("sender", "description"),
                Seq(charlieParty, s"paid 333.0 $amuletNameAcronym for Digestivs"),
              )(row)
            )
            forExactly(1, rows)(row =>
              matchRow(
                Seq("sender", "description"),
                Seq(aliceParty, s"paid 1200.0 $amuletNameAcronym for Team lunch"),
              )(row)
            )
          }
        }
      }

      eventually() {
        // Check final amounts in the wallets
        checkWallet(aliceUserParty, aliceWalletClient, Seq((399.75, 400)))
        checkWallet(bobUserParty, bobWalletClient, Seq((6.7, 7.1)))
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
      initialiseAnsEntry(
        aliceEntryName,
        aliceUserParty,
        aliceAnsExternalClient,
        aliceWalletClient,
      )
      initialiseAnsEntry(
        bobEntryName,
        bobUserParty,
        bobAnsExternalClient,
        bobWalletClient,
      )
      val aliceAns = expectedAns(aliceUserParty, aliceEntryName)
      val bobAns = expectedAns(bobUserParty, bobEntryName)
      bobWalletClient.tap(walletAmuletToUsd(510))

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
        eventually() {
          findAll(className("balances-table-row")).toSeq should have length 1
        }
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
              Seq("ans-entry", "balances-table-amount"),
              Seq(aliceAns, "0.0000000000"),
            )(row)
          }
          inside(findAll(className("balance-updates-list-item")).toSeq.sortBy(_.text)) {
            case Seq(row1, row2) =>
              matchRow(
                Seq("ans-entry", "description"),
                Seq(aliceAns, s"paid 1000.0 $amuletNameAcronym for Team lunch"),
              )(row1)

              matchRow(
                Seq("sender", "description", "receiver"),
                Seq(bobAns, s"sent 500.0 $amuletNameAcronym to", aliceAns),
              )(row2)
          }
        }
      }

      val expectedAliceAmount = walletUsdToAmulet(5 /*ans tap*/ - 1 /*ans fee*/ ) + 500 /*from bob*/
      val expectedBobAmount =
        walletUsdToAmulet(5 /*ans tap*/ - 1.105 /*ans fee*/ ) + 510 /*tap*/ - 500 /*to alice*/
      eventually() {
        // Check final amounts in the wallets
        checkWallet(
          aliceUserParty,
          aliceWalletClient,
          Seq((expectedAliceAmount - walletUsdToAmulet(0.25), expectedAliceAmount)),
        )
        checkWallet(
          bobUserParty,
          bobWalletClient,
          Seq((expectedBobAmount - walletUsdToAmulet(2), expectedBobAmount)),
        )
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
  }
}
