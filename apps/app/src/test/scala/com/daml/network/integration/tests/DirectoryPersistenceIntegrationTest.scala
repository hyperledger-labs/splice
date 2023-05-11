package com.daml.network.integration.tests

import com.daml.network.config.CNDbConfig
import com.daml.network.integration.plugins.UsePostgres
import com.daml.network.integration.tests.CNNodeTests.CNNodeIntegrationTest

// This is just a placeholder until Directory has, and uses, persistent storage
trait DirectoryPersistenceIntegrationTest extends CNNodeIntegrationTest {

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

class DirectoryPostgresIntegrationTest extends DirectoryPersistenceIntegrationTest {
  registerPlugin(new UsePostgres(loggerFactory))
}
