package com.daml.network.integration.tests.runbook

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Preflight test that makes sure that the sequencer url is published to svcRules
  */
class RunbookSvSequencerInfoPreflightIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.svPreflightTopology(
      this.getClass.getSimpleName
    )

  "The SV sequencer public url has been published to SvcRules" in { implicit env =>
    val sv = svcl("sv")
    val svcInfo = sv.getSvcInfo()
    val svcRules = svcInfo.svcRules
    val memberInfo = svcRules.payload.members.asScala.get(svcInfo.svParty.toProtoPrimitive).value
    val domainConfig = memberInfo.domainNodes.asScala.values.headOption.value
    val sequencer = domainConfig.sequencer.toScala.value
    sequencer.url shouldBe s"https://sequencer.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}"
  }
}
