package com.daml.network.environment

import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.svc.{SvcApp, SvcAppBootstrap}
import com.daml.network.svc.config.SvcAppBackendConfig
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbMigrationsFactory

/** SVC app instances. */
class SvcApps(
    create: (String, SvcAppBackendConfig) => SvcAppBootstrap,
    migrationsFactory: DbMigrationsFactory,
    _timeouts: ProcessingTimeout,
    configs: Map[String, SvcAppBackendConfig],
    parametersFor: String => SharedCNNodeAppParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[ // TODO(#736): We should remove the CantonNode/CantonNodeBootstrap type requirements from
      // this trait.
      SvcApp,
      SvcAppBackendConfig,
      SharedCNNodeAppParameters,
      SvcAppBootstrap,
    ](create, migrationsFactory, _timeouts, configs, parametersFor, _loggerFactory) {}
