package com.daml.network.util

import com.daml.network.codegen.java.cn
import com.daml.network.integration.tests.CoinTests.{CoinTestCommon, CoinTestConsoleEnvironment}
import com.digitalasset.canton.topology.PartyId

import scala.jdk.CollectionConverters.*

trait SvTestUtil extends CoinTestCommon {
  this: CommonCoinAppInstanceReferences =>

  def getSvcRules()(implicit env: CoinTestConsoleEnvironment) =
    clue("There is exactly one SvcRules contract") {
      val foundSvcRules = svc.remoteParticipantWithAdminToken.ledger_api_extensions.acs
        .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
      foundSvcRules should have length 1
      foundSvcRules.head
    }

  def addPhantomSv()(implicit env: CoinTestConsoleEnvironment) = {
    // random value for test
    val svXParty = PartyId
      .fromProtoPrimitive(
        "svX::122020c99a2f48cd66782404648771eeaa104f108131c0c876a6ed04dd2e4175f27d"
      )
      .value
    actAndCheck(
      "Add the phantom SV \"svX\"",
      svc.remoteParticipantWithAdminToken.ledger_api_extensions.commands.submitJava(
        actAs = Seq(svcParty),
        optTimeout = None,
        commands = getSvcRules().id
          .exerciseSvcRules_AddMember(svXParty.toProtoPrimitive, "addPhantomSv")
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
