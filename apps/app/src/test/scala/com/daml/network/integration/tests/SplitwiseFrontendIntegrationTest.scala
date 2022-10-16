package com.daml.network.integration.tests

import com.daml.network.environment.{CoinEnvironmentImpl, CoinConsoleEnvironment}
import com.daml.network.integration.CoinEnvironmentDefinition
import com.digitalasset.canton.console.ConsoleMacros
import com.digitalasset.canton.topology.PartyId
import com.daml.network.integration.tests.CoinTests.{CoinTestConsoleEnvironment}
import com.daml.network.codegen.CN.{Splitwise => splitwiseCodegen}
import com.daml.network.codegen.CN.{Directory => dirCodegen}
import com.daml.network.console.{
  RemoteDirectoryAppReference,
  RemoteWalletAppReference,
  RemoteSplitwiseAppReference,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import scala.concurrent.duration.DurationInt
import org.openqa.selenium.Keys

class SplitwiseFrontendIntegrationTest
    extends FrontendIntegrationTest("aliceSplitwise", "bobSplitwise") {

  private val darPath = "apps/splitwise/daml/.daml/dist/splitwise-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        CoinEnvironmentDefinition.simpleTopology(this.getClass.getSimpleName).setup(env)
        aliceValidator.remoteParticipant.dars.upload(darPath)
        bobValidator.remoteParticipant.dars.upload(darPath)
      })

  def initialiseDirectoryApp(
      userName: String,
      userParty: PartyId,
      directory: RemoteDirectoryAppReference,
      wallet: RemoteWalletAppReference,
  )(implicit env: CoinConsoleEnvironment): Unit = {
    directory.requestDirectoryInstall()
    directory.ledgerApi.ledger_api.acs.await(userParty, dirCodegen.DirectoryInstall)

    directory.requestDirectoryEntry(userName)

    wallet.tap(5.0)
    ConsoleMacros.utils.retry_until_true(wallet.listAppPaymentRequests().length == 1)
    wallet.acceptAppPaymentRequest(
      wallet.listAppPaymentRequests().head.contractId
    )
  }

  def initialiseSplitwiseApp(
      splitwise: RemoteSplitwiseAppReference,
      party: PartyId,
  ): Unit = {
    splitwise.createInstallRequest()
    splitwise.ledgerApi.ledger_api.acs.await(party, splitwiseCodegen.SplitwiseInstall)
  }

  /** The `<TextInput>` in ts code is converted by react into a deep tree. This returns the input field. */
  private def reactTextInput(textField: Element): TextField = new TextField(
    textField.childElement(className("MuiInputBase-input")).underlying
  )

  "A splitwise UI" should {

    "settle debts with another party" in { implicit env =>
      val aliceDamlUser = aliceSplitwise.config.damlUser
      val aliceUserParty = aliceValidator.onboardUser(aliceDamlUser)
      val bobDamlUser = bobSplitwise.config.damlUser
      val bobUserParty = bobValidator.onboardUser(bobDamlUser)
      /* TODO(i880) Multi-party settlement
      val charlieDamlUser = charlieRemoteWallet.config.damlUser
      val charlieValidator =
        aliceValidator // we re-use alice's validator here to save some resources
      charlieValidator.onboardUser(charlieDamlUser)
       */
      val groupName = "troika"

      initialiseDirectoryApp("alice.cns", aliceUserParty, aliceDirectory, aliceRemoteWallet)
      initialiseDirectoryApp("bob.cns", bobUserParty, bobDirectory, bobRemoteWallet)
      initialiseSplitwiseApp(aliceSplitwise, aliceUserParty)
      initialiseSplitwiseApp(bobSplitwise, bobUserParty)

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
          .await(bobUserParty, splitwiseCodegen.GroupInvite)
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
      }

      ConsoleMacros.utils.retry_until_true { bobRemoteWallet.listAppPaymentRequests().length == 1 }
      inside(bobRemoteWallet.listAppPaymentRequests()) { case Seq(request) =>
        bobRemoteWallet.tap(510)
        bobRemoteWallet.acceptAppPaymentRequest(request.contractId)
      }

      withFrontEnd("bobSplitwise") { implicit webDriver =>
        click on className(
          "redeem-button"
        ) // I find terminology very confusing - shouldn't alice redeem the payment? I expect this is 'complete/confirm transfer'
        eventually(scaled(5 seconds)) {
          inside(findAll(className("balances-table-row")).toSeq) { case Seq(row) =>
            row.childElement(className("balances-table-receiver")).text shouldBe "alice.cns"
            row.childElement(className("balances-table-quantity")).text.toDouble shouldBe 0.0
          }
          inside(findAll(className("balance-updates-list-item")).toSeq) { case Seq(row1, row2) =>
            row1.text shouldBe "bob.cns sent 500.0000000000 CC to alice.cns"
            row2.text shouldBe "alice.cns paid 1000.0000000000 CC for Team lunch"
          }
        }
      }
    }

  }
}
