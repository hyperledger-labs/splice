package com.daml.network.integration.tests.runbook

import com.daml.network.codegen.java.splice.dso.svstate.SvNodeState
import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Preflight test that makes sure that the sequencer url is published to dsoRules
  */
class RunbookSvSequencerInfoPreflightIntegrationTest extends IntegrationTestWithSharedEnvironment {

  override lazy val resetRequiredTopologyState: Boolean = false

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.svPreflightTopology(
      this.getClass.getSimpleName
    )

  "The SV sequencer public url has been published to DsoRules" in { implicit env =>
    val sv = sv_client("sv")
    val dsoInfo = sv.getDsoInfo()
    val nodeState: SvNodeState = dsoInfo.svNodeStates.get(dsoInfo.svParty).value.payload
    val domainConfig = nodeState.state.synchronizerNodes.asScala.values.headOption.value
    val sequencer = domainConfig.sequencer.toScala.value
    sequencer.migrationId shouldBe migrationId
    sequencer.url shouldBe s"https://sequencer-${migrationId}.sv.${sys.env("NETWORK_APPS_ADDRESS")}"
  }
}
