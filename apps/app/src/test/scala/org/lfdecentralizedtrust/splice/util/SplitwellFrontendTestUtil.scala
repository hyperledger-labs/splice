package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon
import com.digitalasset.canton.topology.PartyId

trait SplitwellFrontendTestUtil extends TestCommon with AnsTestUtil {
  this: CommonAppInstanceReferences & FrontendTestCommon =>

  def addTeamLunch(quantity: Double)(implicit webDriver: WebDriverType) = {
    inside(find(className("enter-payment-amount-field"))) { case Some(field) =>
      field.underlying.click()
      reactTextInput(field).value = quantity.toString
    }
    inside(find(className("enter-payment-description-field"))) { case Some(field) =>
      field.underlying.click()
      reactTextInput(field).value = "Team lunch"
    }
    click on className("enter-payment-link")
  }

  def enterSplitwellPayment(
      receiver: String,
      receiverPartyId: PartyId,
      quantity: Double,
  )(implicit
      webDriver: WebDriverType
  ) = {
    eventually() {
      inside(find(className("transfer-amount-field"))) { case Some(field) =>
        field.underlying.click()
        reactTextInput(field).value = quantity.toString
      }
      setAnsField(
        reactTextInput(find(className("transfer-receiver-field")).value),
        receiver,
        receiverPartyId.toProtoPrimitive,
      )
      click on className("transfer-link")
    }
  }

  def createGroup(groupName: String)(implicit webDriver: WebDriverType) = {
    waitForQuery(id("group-id-field"))
    click on "group-id-field"
    textField("group-id-field").value = groupName
    click on "create-group-button"
  }
  def createGroupAndInviteLink(groupName: String)(implicit
      webDriver: WebDriverType
  ): String = {
    createGroup(groupName)

    eventually() {
      val createInviteElements = findAll(className("create-invite-link"))
      withClue(s"Create invite elements not found $createInviteElements") {
        val createInviteButton = createInviteElements
          .filter(_.attribute("data-group").contains(groupName))
          .toSeq
          .loneElement
        click on createInviteButton
      }
    }
    eventually() {
      val inviteCopyElements = findAll(className("invite-copy-button"))
      withClue(s"Invite copy button not found $inviteCopyElements") {
        val inviteCopyButton = inviteCopyElements
          .filter(_.attribute("data-group-id").contains(groupName))
          .toSeq
          .loneElement
        inviteCopyButton.attribute("data-invite-contract").value
      }
    }
  }

  def requestGroupMembership(invite: String)(implicit webDriver: WebDriverType) = {
    val field = eventually() {
      textField(id("group-invite-field"))
    }
    field.value = invite
    val link = eventually() {
      find(id("request-membership-link")).valueOrFail("Request membership link not found")
    }
    click on link
  }

  def getGroupContractIds()(implicit driver: WebDriverType): Set[String] =
    // The element is hidden so we need to use .attribute("textContent") instead of .text
    findAll(className("data-group-contract-id")).map(_.attribute("textContent").value).toSet
}
