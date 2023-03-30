package com.daml.network.util

import com.daml.network.codegen.java.cn
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.topology.PartyId

import scala.jdk.CollectionConverters.*

trait SvTestUtil extends CNNodeTestCommon {
  this: CommonCNNodeAppInstanceReferences =>

  def getSvcRules()(implicit env: CNNodeTestConsoleEnvironment) =
    clue("There is exactly one SvcRules contract") {
      val foundSvcRules = svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
      foundSvcRules should have length 1
      foundSvcRules.head
    }

  def allocationRandomParty()(implicit env: CNNodeTestConsoleEnvironment) = {
    val id = (new scala.util.Random).nextInt().toHexString
    PartyId
      .fromLfParty(svc.remoteParticipant.ledger_api.parties.allocate(s"svX-$id", "svX").party)
      .value
  }

  def addPhantomSv()(implicit env: CNNodeTestConsoleEnvironment) = {
    // random value for test
    val svXParty = allocationRandomParty()
    actAndCheck(
      "Add the phantom SV \"svX\"",
      svc.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
        actAs = Seq(svcParty),
        optTimeout = None,
        commands = getSvcRules().id
          .exerciseSvcRules_AddMember(
            svXParty.toProtoPrimitive,
            "svX, the phantom of the SVC",
            "addPhantomSv",
          )
          .commands
          .asScala
          .toSeq,
      ),
    )(
      "SvX is a member of the SvcRules",
      _ => getSvcRules().data.members should contain key svXParty.toProtoPrimitive,
    )
  }
}
