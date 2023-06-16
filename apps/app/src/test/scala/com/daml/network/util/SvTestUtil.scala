package com.daml.network.util

import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cn
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.topology.PartyId

import scala.jdk.CollectionConverters.*

trait SvTestUtil extends CNNodeTestCommon {
  this: CommonCNNodeAppInstanceReferences =>

  protected def svs(implicit env: CNNodeTestConsoleEnvironment) = Seq(sv1, sv2, sv3, sv4)

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
    val nextMiningRound = clue("Getting next open round") {
      val (openRounds, _) = sv1Scan.getOpenAndIssuingMiningRounds()
      new Round(openRounds.map(_.contract.payload.round.number).max + 1)
    }
    actAndCheck(
      s"Add the phantom SV \"$svName\"",
      svc.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
        actAs = Seq(svcParty),
        optTimeout = None,
        commands = svc.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
          .head
          .id
          .exerciseSvcRules_AddMember(
            svParty.toProtoPrimitive,
            svName,
            nextMiningRound,
          )
          .commands
          .asScala
          .toSeq,
      ),
    )(
      s"$svName is a member of the SvcRules",
      _ =>
        svc.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
          .head
          .data
          .members should contain key svParty.toProtoPrimitive,
    )
  }
}
