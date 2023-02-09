package com.daml.network.environment

import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.splitwell.config.SplitwellAppBackendConfig
import com.daml.network.splitwell.{SplitwellApp, SplitwellAppBootstrap}
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbMigrationsFactory

/** Splitwell app instances. */
class SplitwellApps(
    create: (String, SplitwellAppBackendConfig) => SplitwellAppBootstrap,
    migrationsFactory: DbMigrationsFactory,
    _timeouts: ProcessingTimeout,
    configs: Map[String, SplitwellAppBackendConfig],
    parametersFor: String => SharedCoinAppParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[ // TODO(#736): We should remove the CantonNode/CantonNodeBootstrap type requirements from
      // this trait.
      SplitwellApp,
      SplitwellAppBackendConfig,
      SharedCoinAppParameters,
      SplitwellAppBootstrap,
    ](create, migrationsFactory, _timeouts, configs, parametersFor, _loggerFactory) {}
