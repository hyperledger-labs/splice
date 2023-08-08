package com.daml.network.util

import com.daml.network.codegen.java.cc.api.v1.round.Round
import com.daml.network.codegen.java.cn
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.topology.{ParticipantId, PartyId}

import scala.jdk.CollectionConverters.*

trait SvTestUtil extends CNNodeTestCommon {
  this: CommonCNNodeAppInstanceReferences =>

  protected def svs(implicit env: CNNodeTestConsoleEnvironment) =
    Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)

  def allocateRandomSvParty(name: String)(implicit env: CNNodeTestConsoleEnvironment) = {
    val id = (new scala.util.Random).nextInt().toHexString
    sv1Backend.participantClient.ledger_api.parties.allocate(s"$name-$id", name).party
  }

  def addPhantomSv()(implicit env: CNNodeTestConsoleEnvironment) = {
    // random value for test
    val svXParty = allocateRandomSvParty("svX")
    addSvMember(svXParty, "svX, the phantom of the SVC", sv1Backend.participantClient.id)
  }

  def median(values: Seq[BigDecimal]): Option[BigDecimal] = {
    if (values != null && values.length > 0) {
      val sorted = values.sorted(Ordering[BigDecimal])
      val length = sorted.length
      val half = length / 2
      if (length % 2 != 0)
        Some(sorted(half))
      else
        Some((sorted(half - 1) + sorted(half)) / BigDecimal.valueOf(2))
    } else {
      None
    }
  }

  def addSvMember(
      svParty: PartyId,
      svName: String,
      svParticipantId: ParticipantId,
  )(implicit env: CNNodeTestConsoleEnvironment) = {
    val nextMiningRound = clue("Getting next open round") {
      val (openRounds, _) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
      new Round(openRounds.map(_.contract.payload.round.number).max + 1)
    }
    val coinConfig = sv1ScanBackend.getCoinConfigAsOf(env.environment.clock.now)
    actAndCheck(
      s"Add the phantom SV \"$svName\"",
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
        actAs = Seq(svcParty),
        optTimeout = None,
        commands = sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
          .head
          .id
          .exerciseSvcRules_AddMember(
            svParty.toProtoPrimitive,
            svName,
            svParticipantId.toProtoPrimitive,
            nextMiningRound,
            coinConfig.globalDomain.activeDomain,
          )
          .commands
          .asScala
          .toSeq,
      ),
    )(
      s"$svName is a member of the SvcRules",
      _ =>
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
          .head
          .data
          .members should contain key svParty.toProtoPrimitive,
    )
  }
}
