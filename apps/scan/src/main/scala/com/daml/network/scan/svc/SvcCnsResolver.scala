package com.daml.network.scan.svc

import com.daml.network.util.Contract
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.http.v0.definitions as http
import com.daml.network.scan.svc.SvcCnsResolver.{SvcCnsEntry, svcCnsName, svCnsNameSuffix}
import com.digitalasset.canton.topology.PartyId

import scala.jdk.CollectionConverters.*

class SvcCnsResolver(svcParty: PartyId) {
  private val svcCnsEntry: SvcCnsEntry = SvcCnsEntry(svcParty, svcCnsName)
  private val svCnsNamePattern = """^[a-zA-Z0-9_-]+\.sv\.ans$""".r

  def lookupEntryByName(
      svcRules: Contract[SvcRules.ContractId, SvcRules],
      cnsName: String,
  ): Option[SvcCnsEntry] =
    cnsName match {
      case `svcCnsName` => Some(svcCnsEntry)
      case svCnsNamePattern() =>
        val maybeSvPartyId = svcRules.payload.members.asScala.collectFirst {
          case (svPartyId, memberInfo) if cnsName == svCnsName(memberInfo.name) =>
            PartyId.tryFromProtoPrimitive(svPartyId) -> memberInfo.name
        }
        maybeSvPartyId.map { case (svPartyId, memberName) =>
          svCnsEntry(svPartyId, memberName.toLowerCase())
        }
      case _ => None
    }

  def lookupEntryByParty(
      svcRules: Contract[SvcRules.ContractId, SvcRules],
      party: PartyId,
  ): Option[SvcCnsEntry] =
    party match {
      case `svcParty` => Some(svcCnsEntry)
      case _ =>
        svcRules.payload.members.asScala
          .get(party.toProtoPrimitive)
          .map(memberInfo => svCnsEntry(party, memberInfo.name))
    }

  def listEntries(
      svcRules: Contract[SvcRules.ContractId, SvcRules],
      namePrefix: Option[String],
  ): Seq[SvcCnsEntry] = {
    val entries = svcCnsEntry +: svcRules.payload.members.asScala.collect {
      case (svParty, memberInfo) =>
        svCnsEntry(PartyId.tryFromProtoPrimitive(svParty), memberInfo.name)
    }.toSeq
    namePrefix.fold(entries)(prefix => entries.filter(_.name.startsWith(prefix)))
  }

  private def svCnsEntry(svParty: PartyId, svName: String) = SvcCnsEntry(svParty, svCnsName(svName))

  private def svCnsName(svName: String) = s"${svName.toLowerCase}$svCnsNameSuffix"
}

object SvcCnsResolver {
  val svcCnsName = "dso.ans"
  val svCnsNameSuffix = ".sv.ans"
  case class SvcCnsEntry(user: PartyId, name: String) {
    def toHttp: http.CnsEntry = http.CnsEntry(None, user.toProtoPrimitive, name, "", "", None)
  }
}
