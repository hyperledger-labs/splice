package com.daml.network.integration.tests

import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{
  CoinIntegrationTest,
  CoinTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

import java.time.Duration

class DirectoryTimeBasedIntegrationTest extends CoinIntegrationTest {

  private val directoryDarPath =
    "apps/directory/daml/.daml/dist/directory-service-0.1.0.dar"
  private val testEntryName = "mycoolentry"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopologyWithSimTime(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(directoryDarPath)
        bobValidator.remoteParticipant.dars.upload(directoryDarPath)
      })

  "we can control time" in { implicit env =>
    // TODO(i1425) Replace this with an actual directory test that uses time manipulation.
    val start_time = aliceValidator.remoteParticipant.ledger_api.time.get()
    advanceTime(Duration.ofDays(23))
    val end_time = aliceValidator.remoteParticipant.ledger_api.time.get()
    end_time shouldBe start_time.plus(Duration.ofDays(23))
  }
}
