package com.daml.network.environment

import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.splitwise.config.SplitwiseAppBackendConfig
import com.daml.network.splitwise.{SplitwiseApp, SplitwiseAppBootstrap}
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbMigrationsFactory

/** Splitwise app instances. */
class SplitwiseApps(
    create: (String, SplitwiseAppBackendConfig) => SplitwiseAppBootstrap,
    migrationsFactory: DbMigrationsFactory,
    _timeouts: ProcessingTimeout,
    configs: Map[String, SplitwiseAppBackendConfig],
    parametersFor: String => SharedCoinAppParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[ // TODO(#736): We should remove the CantonNode/CantonNodeBootstrap type requirements from
      // this trait.
      SplitwiseApp,
      SplitwiseAppBackendConfig,
      SharedCoinAppParameters,
      SplitwiseAppBootstrap,
    ](create, migrationsFactory, _timeouts, configs, parametersFor, _loggerFactory) {}
