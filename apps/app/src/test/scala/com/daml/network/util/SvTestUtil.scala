package com.daml.network.util

import com.daml.ledger.javaapi.data.TransactionTreeV2
import com.daml.network.codegen.java.cn
import com.daml.network.codegen.java.cn.svcrules.ActionRequiringConfirmation
import com.daml.network.console.{CNParticipantClientReference, SvAppBackendReference}
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeTestCommon,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.SvTestUtil.ConfirmingSv
import com.digitalasset.canton.topology.PartyId

import scala.jdk.CollectionConverters.*

trait SvTestUtil extends CNNodeTestCommon {
  this: CommonCNNodeAppInstanceReferences =>

  protected def svs(implicit env: CNNodeTestConsoleEnvironment) =
    Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)

  def allocateRandomSvParty(name: String)(implicit env: CNNodeTestConsoleEnvironment) = {
    val id = (new scala.util.Random).nextInt().toHexString
    sv1Backend.participantClient.ledger_api.parties.allocate(s"$name-$id", name).party
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

  def confirmActionByAllMembers(
      confirmingSvs: Seq[ConfirmingSv],
      action: ActionRequiringConfirmation,
  )(implicit env: CNNodeTestConsoleEnvironment): Seq[TransactionTreeV2] = {
    confirmingSvs.map { case ConfirmingSv(participantClient, svPartyId) =>
      confirmAction(participantClient, svPartyId, action)
    }
  }

  private def confirmAction(
      participantClient: CNParticipantClientReference,
      svPartyId: PartyId,
      action: ActionRequiringConfirmation,
  )(implicit env: CNNodeTestConsoleEnvironment): TransactionTreeV2 = {
    eventuallySucceeds() {
      val svcRulesCid = participantClient.ledger_api_extensions.acs
        .filterJava(cn.svcrules.SvcRules.COMPANION)(svcParty)
        .head
        .id
      participantClient.ledger_api_extensions.commands
        .submitJava(
          actAs = Seq(svPartyId),
          readAs = Seq(svcParty),
          optTimeout = None,
          commands = svcRulesCid
            .exerciseSvcRules_ConfirmAction(svPartyId.toProtoPrimitive, action)
            .commands
            .asScala
            .toSeq,
        )
    }
  }

  def getConfirmingSvs(svBackends: Seq[SvAppBackendReference]): Seq[ConfirmingSv] =
    svBackends.map(sv => ConfirmingSv(sv.participantClientWithAdminToken, sv.getSvcInfo().svParty))
}

object SvTestUtil {
  case class ConfirmingSv(svParticipantClient: CNParticipantClientReference, svPartyId: PartyId)
}
