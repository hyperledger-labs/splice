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
      val foundSvcRules = svc.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
      foundSvcRules should have length 1
      foundSvcRules.head
    }

  def allocateRandomSvParty(name: String)(implicit env: CNNodeTestConsoleEnvironment) = {
    val id = (new scala.util.Random).nextInt().toHexString
    svc.participantClient.ledger_api.parties.allocate(s"$name-$id", name).party
  }

  def addPhantomSv()(implicit env: CNNodeTestConsoleEnvironment) = {
    // random value for test
    val svXParty = allocateRandomSvParty("svX")
    addSvMember(svXParty, "svX, the phantom of the SVC")
  }

  def addSvMember(
      svParty: PartyId,
      svName: String,
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    actAndCheck(
      s"Add the phantom SV \"$svName\"",
      svc.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
        actAs = Seq(svcParty),
        optTimeout = None,
        commands = getSvcRules().id
          .exerciseSvcRules_AddMember(
            svParty.toProtoPrimitive,
            svName,
          )
          .commands
          .asScala
          .toSeq,
      ),
    )(
      s"$svName is a member of the SvcRules",
      _ => getSvcRules().data.members should contain key svParty.toProtoPrimitive,
    )
  }
}
