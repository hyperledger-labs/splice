package org.lfdecentralizedtrust.splice.integration.tests.runbook

import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvNodeState
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** Preflight test that makes sure that the sequencer url is published to dsoRules
  */
class RunbookSvSequencerInfoPreflightIntegrationTest
    extends IntegrationTest
    with PreflightIntegrationTestUtil {

  override lazy val resetRequiredTopologyState: Boolean = false
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.svPreflightTopology(
      this.getClass.getSimpleName
    )

  "The SV sequencer public url has been published to DsoRules" in { implicit env =>
    val dsoInfo = eventuallySucceeds() {
      scancl("svScan").getDsoInfo()
    }
    val nodeState: SvNodeState = dsoInfo.svNodeStates.get(dsoInfo.svParty).value.payload
    val domainConfig = nodeState.state.synchronizerNodes.asScala.values.headOption.value
    val sequencerUrls = domainConfig.physicalSynchronizers.toScala.toList
      .flatMap(_.asScala.values.flatMap(_.sequencer.toScala.map(_.url)))
    sequencerUrls should contain(
      s"https://sequencer-$migrationId.sv.${sys.env("NETWORK_APPS_ADDRESS")}"
    )
  }
}
