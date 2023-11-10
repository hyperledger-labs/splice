package com.daml.network.sv.automation.singlesv.onboarding

import com.daml.network.codegen.java.cn.svc.globaldomain.DomainNodeConfig
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.topology.DomainId

import scala.jdk.CollectionConverters.MapHasAsScala

object OnboardingDomainNodeUtil {

  def currentDomainConfig(
      domain: DomainId,
      svcRules: AssignedContract[SvcRules.ContractId, SvcRules],
  ): Seq[DomainNodeConfig] = {
    svcRules.contract.payload.members.asScala.values
      .flatMap(_.domainNodes.asScala.get(domain.toProtoPrimitive))
      .toSeq
  }

}
