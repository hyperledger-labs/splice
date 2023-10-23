package com.daml.network.integration.plugins

import com.daml.network.config.{CNDbConfig, CNNodeConfig, CNNodeConfigTransforms}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.digitalasset.canton.logging.NamedLoggerFactory

// TODO (#8152): remove this and all tests using it.
class UseInMemoryStores(protected val loggerFactory: NamedLoggerFactory)
    extends EnvironmentSetupPlugin[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] {
  override def beforeEnvironmentCreated(config: CNNodeConfig): CNNodeConfig = {
    val storageChange = CNNodeConfigTransforms.modifyAllCNStorageConfigs { (_, _) =>
      CNDbConfig.Memory()
    }
    storageChange(config)
  }
}
