package com.daml.network.environment

import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.sv.{SvApp, SvAppBootstrap}
import com.daml.network.sv.config.LocalSvAppConfig
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbMigrationsFactory

/** SV app instances. */
class SvApps(
    create: (String, LocalSvAppConfig) => SvAppBootstrap,
    migrationsFactory: DbMigrationsFactory,
    _timeouts: ProcessingTimeout,
    configs: Map[String, LocalSvAppConfig],
    parametersFor: String => SharedCNNodeAppParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[ // TODO(#736): We should remove the CantonNode/CantonNodeBootstrap type requirements from
      // this trait.
      SvApp,
      LocalSvAppConfig,
      SharedCNNodeAppParameters,
      SvAppBootstrap,
    ](create, migrationsFactory, _timeouts, configs, parametersFor, _loggerFactory) {}
