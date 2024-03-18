package com.daml.network.integration.tests.runbook

import com.daml.network.codegen.java.cn.svc.memberstate.SvNodeState
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
    extends CNNodeIntegrationTestWithSharedEnvironment
    with DomainMigrationIntegrationTestUtil {

  override lazy val resetRequiredTopologyState: Boolean = false

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition.svPreflightTopology(
      this.getClass.getSimpleName
    )

  "The SV sequencer public url has been published to SvcRules" in { implicit env =>
    val sv = svcl("sv")
    val svcInfo = sv.getSvcInfo()
    val nodeState: SvNodeState = svcInfo.svNodeStates.get(svcInfo.svParty).value.payload
    val domainConfig = nodeState.state.domainNodes.asScala.values.headOption.value
    val sequencer = domainConfig.sequencer.toScala.value
    sequencer.migrationId shouldBe migrationId
    sequencer.url shouldBe s"https://sequencer-${migrationId}.sv.svc.${sys.env("NETWORK_APPS_ADDRESS")}"
  }
}
