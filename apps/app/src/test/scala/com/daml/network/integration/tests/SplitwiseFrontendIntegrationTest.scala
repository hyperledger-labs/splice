package com.daml.network.integration.tests

import com.daml.network.codegen.OpenBusiness.Fees.{ExpiringQuantity, RatePerRound}
import com.daml.network.codegen.java.cn.{directory as dirCodegen, splitwise as splitwiseCodegen}
import com.daml.network.console.{RemoteDirectoryAppReference, RemoteWalletAppReference}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.util.CoinUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import org.openqa.selenium.Keys

import scala.concurrent.duration.DurationInt

class SplitwiseFrontendIntegrationTest
    extends FrontendIntegrationTest("aliceSplitwise", "bobSplitwise", "charlieSplitwise") {

  private val splitwiseDarPath = "apps/splitwise/daml/.daml/dist/splitwise-0.1.0.dar"
  private val directoryDarPath = "apps/directory/daml/.daml/dist/directory-service-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        CoinEnvironmentDefinition.simpleTopology(this.getClass.getSimpleName).setup(env)
        Seq(splitwiseDarPath, directoryDarPath).foreach { path =>
          aliceValidator.remoteParticipant.dars.upload(path)
          bobValidator.remoteParticipant.dars.upload(path)
        }
      })

  def initialiseDirectoryApp(
      userName: String,
      userParty: PartyId,
      directory: RemoteDirectoryAppReference,
      wallet: RemoteWalletAppReference,
  ): Unit = {
    directory.requestDirectoryInstall()
    directory.ledgerApi.ledger_api.acs.awaitJava(dirCodegen.DirectoryInstall.COMPANION)(userParty)

    directory.requestDirectoryEntry(userName)

    wallet.tap(5.0)
    eventually() { wallet.listAppPaymentRequests().length shouldBe 1 }
    wallet.acceptAppPaymentRequest(
      wallet.listAppPaymentRequests().head.contractId
    )
  }

  /** The `<TextInput>` in ts code is converted by react into a deep tree. This returns the input field. */
  private def reactTextInput(textField: Element): TextField = new TextField(
    textField.childElement(className("MuiInputBase-input")).underlying
  )

  "A splitwise UI" should {

    "settle debts with multiple parties" in { implicit env =>
      val aliceDamlUser = aliceSplitwise.config.damlUser
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)
      val bobDamlUser = bobSplitwise.config.damlUser
      val bobUserParty = bobValidator.onboardUser(bobDamlUser)
      val charlieDamlUser = charlieSplitwise.config.damlUser
      // we re-use alice's validator here to save some resources
      val charlieValidator = aliceValidator
      val charlieUserParty = charlieValidator.onboardUser(charlieDamlUser)
      val groupName = "troika"

      initialiseDirectoryApp("alice.cns", aliceUserParty, aliceDirectory, aliceRemoteWallet)
      initialiseDirectoryApp("bob.cns", bobUserParty, bobDirectory, bobRemoteWallet)
      initialiseDirectoryApp("charlie.cns", charlieUserParty, charlieDirectory, charlieRemoteWallet)
      val aliceCns = expectedCns(aliceUserParty, "alice.cns")
      val bobCns = expectedCns(bobUserParty, "bob.cns")
      val charlieCns = expectedCns(charlieUserParty, "charlie.cns")

      bobRemoteWallet.tap(550)

      withFrontEnd("aliceSplitwise") { implicit webDriver =>
        go to "http://localhost:3002"
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"
        click on "group-id-field"
        textField("group-id-field").value = groupName
        click on "create-group-button"
        click on "create-invite-link"
      }

      withFrontEnd("bobSplitwise") { implicit webDriver =>
        go to "http://localhost:3003"
        click on "user-id-field"
        textField("user-id-field").value = bobDamlUser
        click on "login-button"
        bobValidator.remoteParticipant.ledger_api.acs
          .awaitJava(splitwiseCodegen.GroupInvite.COMPANION)(bobUserParty)
        click on "request-membership-link"
      }

      withFrontEnd("aliceSplitwise") { implicit webDriver =>
        click on className("add-user-link")
      }

      withFrontEnd("charlieSplitwise") { implicit webDriver =>
        go to "http://localhost:3005"
        click on "user-id-field"
        textField("user-id-field").value = charlieDamlUser
        click on "login-button"
        charlieValidator.remoteParticipant.ledger_api.acs
          .awaitJava(splitwiseCodegen.GroupInvite.COMPANION)(charlieUserParty)
        click on "request-membership-link"
      }

      withFrontEnd("aliceSplitwise") { implicit webDriver =>
        click on className("add-user-link")

        inside(find(className("enter-payment-quantity-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "1200.0"
        }
        inside(find(className("enter-payment-description-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "Team lunch"
        }
        click on className("enter-payment-link")
      }

      withFrontEnd("charlieSplitwise") { implicit webDriver =>
        inside(find(className("enter-payment-quantity-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "333.0"
        }
        inside(find(className("enter-payment-description-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "Digestivs"
        }
        click on className("enter-payment-link")
      }

      withFrontEnd("bobSplitwise") { implicit webDriver =>
        eventually() {
          inside(findAll(className("balances-table-row")).toSeq) {
            case Seq(r1, r2) => // Need to sync here on the actual values (size not enough)
              r1.childElement(className("balances-table-receiver")).text should matchText(
                aliceCns
              )
              r1.childElement(className("balances-table-quantity")).text shouldBe "-400.0000000000"
              r2.childElement(className("balances-table-receiver")).text should matchText(
                charlieCns
              )
              r2.childElement(className("balances-table-quantity")).text shouldBe "-111.0000000000"
          }
        }
        click on className("settle-my-debts-link")

        // Bob is redirected to wallet ..
        click on "user-id-field"
        textField("user-id-field").value = bobDamlUser
        click on "login-button"

        click on className("accept-button")

        // And then back to splitwise
        click on "user-id-field"
        textField("user-id-field").value = bobDamlUser
        click on "login-button"

        eventually() {
          inside(findAll(className("balances-table-row")).toSeq) { case Seq(row1, row2) =>
            row1.childElement(className("balances-table-receiver")).text should matchText(
              aliceCns
            )
            row1.childElement(className("balances-table-quantity")).text shouldBe "0.0000000000"
            row2.childElement(className("balances-table-receiver")).text should matchText(
              charlieCns
            )
            row2.childElement(className("balances-table-quantity")).text shouldBe "0.0000000000"
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
        val exactly = (x: BigDecimal) => (x, x)
        checkWallet(aliceUserParty, aliceRemoteWallet, Seq((3.75, 4), exactly(400)))
        checkWallet(bobUserParty, bobRemoteWallet, Seq((40.4, 40.5)))
        checkWallet(charlieUserParty, charlieRemoteWallet, Seq((3.75, 4), exactly(111)))
      }
    }

    def checkWallet(
        walletParty: PartyId,
        wallet: RemoteWalletAppReference,
        expectedQuantityRanges: Seq[(BigDecimal, BigDecimal)],
    ): Unit = clue(s"checking wallet with $expectedQuantityRanges") {
      eventually(10.seconds, 500.millis) {
        val coins =
          wallet.list().coins.sortBy(coin => coin.contract.payload.quantity.initialQuantity)
        coins should have size (expectedQuantityRanges.size.toLong)
        coins
          .zip(expectedQuantityRanges)
          .foreach { case (coin, quantityBounds) =>
            coin.contract.payload.owner shouldBe walletParty.toPrim
            val ExpiringQuantity(initialQuantity, _, ratePerRound) =
              coin.contract.payload.quantity
            assertInRange(initialQuantity, quantityBounds)
            ratePerRound shouldBe RatePerRound(
              CoinUtil.defaultHoldingFee.rate.doubleValue
            )
          }
      }
    }

    def assertInRange(value: BigDecimal, range: (BigDecimal, BigDecimal)): Unit = {
      value should (be >= range._1 and be <= range._2)
    }

    "settle debts with a single party" in { implicit env =>
      val aliceDamlUser = aliceSplitwise.config.damlUser
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)
      val bobDamlUser = bobSplitwise.config.damlUser
      val bobUserParty = bobValidator.onboardUser(bobDamlUser)
      val groupName = "troika"

      initialiseDirectoryApp("alice.cns", aliceUserParty, aliceDirectory, aliceRemoteWallet)
      initialiseDirectoryApp("bob.cns", bobUserParty, bobDirectory, bobRemoteWallet)
      val aliceCns = expectedCns(aliceUserParty, "alice.cns")
      val bobCns = expectedCns(bobUserParty, "bob.cns")
      bobRemoteWallet.tap(510)

      withFrontEnd("aliceSplitwise") { implicit webDriver =>
        go to "http://localhost:3002"
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"
        click on "group-id-field"
        textField("group-id-field").value = groupName
        click on "create-group-button"
        click on "create-invite-link"
      }

      withFrontEnd("bobSplitwise") { implicit webDriver =>
        go to "http://localhost:3003"
        click on "user-id-field"
        textField("user-id-field").value = bobDamlUser
        click on "login-button"
        bobValidator.remoteParticipant.ledger_api.acs
          .awaitJava(splitwiseCodegen.GroupInvite.COMPANION)(bobUserParty)
        click on "request-membership-link"
      }

      withFrontEnd("aliceSplitwise") { implicit webDriver =>
        click on className("add-user-link")
        inside(find(className("enter-payment-quantity-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "1000.0"
        }
        inside(find(className("enter-payment-description-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "Team lunch"
        }
        click on className("enter-payment-link")
      }

      withFrontEnd("bobSplitwise") { implicit webDriver =>
        inside(find(className("transfer-quantity-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "500"
        }
        inside(find(className("transfer-receiver-field"))) { case Some(field) =>
          field.underlying.click()
          val input = reactTextInput(field)
          input.underlying.sendKeys("alice")
          input.underlying.sendKeys(Keys.ARROW_DOWN)
          input.underlying.sendKeys(Keys.ENTER)
        }
        click on className("transfer-link")

        // Bob is redirected to wallet ..
        click on "user-id-field"
        textField("user-id-field").value = bobDamlUser
        click on "login-button"

        click on className("accept-button")

        // And then back to splitwise
        click on "user-id-field"
        textField("user-id-field").value = bobDamlUser
        click on "login-button"

        eventually(scaled(5 seconds)) {
          inside(findAll(className("balances-table-row")).toSeq) { case Seq(row) =>
            row.childElement(className("balances-table-receiver")).text should matchText(aliceCns)
            row.childElement(className("balances-table-quantity")).text.toDouble shouldBe 0.0
          }
          inside(findAll(className("balance-updates-list-item")).toSeq) { case Seq(row1, row2) =>
            row1.text should matchText(s"${bobCns} sent 500.0000000000 CC to ${aliceCns}")
            row2.text should matchText(s"${aliceCns} paid 1000.0000000000 CC for Team lunch")
          }
        }
      }

      val exactly = (x: BigDecimal) => (x, x)
      eventually() {
        // Check final amounts in the wallets
        checkWallet(aliceUserParty, aliceRemoteWallet, Seq((3.75, 4), exactly(500)))
        checkWallet(bobUserParty, bobRemoteWallet, Seq((12.4, 12.5)))
      }
    }

  }
}
