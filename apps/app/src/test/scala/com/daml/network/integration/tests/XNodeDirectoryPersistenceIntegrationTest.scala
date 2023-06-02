package com.daml.network.integration.tests

import com.daml.network.config.CNDbConfig
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.plugins.UsePostgres
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition

// This is just a placeholder until Directory has, and uses, persistent storage
trait XNodeDirectoryPersistenceIntegrationTest extends CNNodeIntegrationTest {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyXCentralizedDomain(this.getClass.getSimpleName)

  "Directory" should {

    "start up with the configured persistent storage" in { implicit env =>
      env.actualConfig.directoryApp.map(_.storage).valueOrFail("No storage found.") match {
        case _: CNDbConfig.Memory => fail("type=memory found")
        case _: CNDbConfig.Postgres => ()
      }

      // check directory is up and running.
      directory.listEntries("", 1) shouldBe empty
    }

  }

}

class XNodeDirectoryPostgresIntegrationTest extends XNodeDirectoryPersistenceIntegrationTest {
  registerPlugin(new UsePostgres(loggerFactory))
}
