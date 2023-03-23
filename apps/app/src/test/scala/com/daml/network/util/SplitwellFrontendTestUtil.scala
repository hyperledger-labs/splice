package com.daml.network.util

import com.daml.network.integration.tests.CNNodeTests.CNNodeTestCommon
import com.daml.network.integration.tests.FrontendTestCommon
import com.digitalasset.canton.topology.PartyId

trait SplitwellFrontendTestUtil extends CNNodeTestCommon with CnsTestUtil {
  this: CommonCNNodeAppInstanceReferences & FrontendTestCommon =>

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
    inside(find(className("transfer-amount-field"))) { case Some(field) =>
      field.underlying.click()
      reactTextInput(field).value = quantity.toString
    }
    setDirectoryField(
      reactTextInput(find(className("transfer-receiver-field")).value),
      receiver,
      receiverPartyId.toProtoPrimitive,
    )
    click on className("transfer-link")
  }

  def createGroup(groupName: String)(implicit webDriver: WebDriverType) = {
    click on "group-id-field"
    textField("group-id-field").value = groupName
    click on "create-group-button"
  }
  def createGroupAndInviteLink(groupName: String)(implicit
      webDriver: WebDriverType
  ): String = {
    createGroup(groupName)
    eventually() {
      inside(
        findAll(className("create-invite-link"))
          .filter(_.attribute("data-group") == Some(groupName))
          .toSeq
      ) { case Seq(button) =>
        click on button
      }
    }
    eventually() {
      inside(
        findAll(className("invite-copy-button"))
          .filter(_.attribute("data-group-id") == Some(groupName))
          .toSeq
      ) { case Seq(button) =>
        button.attribute("data-invite-contract").value
      }
    }
  }

  def requestGroupMembership(invite: String)(implicit webDriver: WebDriverType) = {
    val field = textField(id("group-invite-field"))
    field.value = invite
    click on id("request-membership-link")
  }

  def getGroupContractIds()(implicit driver: WebDriverType): Set[String] =
    // The element is hidden so we need to use .attribute("textContent") instead of .text
    findAll(className("data-group-contract-id")).map(_.attribute("textContent").value).toSet
}
