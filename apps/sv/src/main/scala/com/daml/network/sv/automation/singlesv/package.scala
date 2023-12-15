package com.daml.network.sv.automation

import com.daml.network.codegen.java.cn.svc.globaldomain.SequencerConfig
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.topology.{DomainId, PartyId}

import java.time.Instant

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

package object singlesv {
  def getAvailableSequencerConfigFromSvcRules(
      svParty: PartyId,
      svcRules: AssignedContract[SvcRules.ContractId, SvcRules],
      time: Instant,
      globalDomainId: DomainId,
  ): Option[SequencerConfig] = for {
    memberInfo <- svcRules.payload.members.asScala.get(svParty.toProtoPrimitive)
    domainNodeConfig <- memberInfo.domainNodes.asScala.get(globalDomainId.toProtoPrimitive)
    sequencerConfig <- domainNodeConfig.sequencer.toScala
    if sequencerConfig.url.nonEmpty && sequencerConfig.availableAfter.toScala.exists(
      availableAfter => time.isAfter(availableAfter)
    )
  } yield sequencerConfig
}
