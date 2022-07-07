package com.daml.network.environment

import com.daml.network.svc.config.{LocalSvcAppConfig, SvcAppParameters}
import com.daml.network.svc.{SvcAppNode, SvcAppBootstrap}
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbMigrationsFactory

/** SVC app instances. */
class SvcApps(
    create: (String, LocalSvcAppConfig) => SvcAppBootstrap,
    migrationsFactory: DbMigrationsFactory,
    _timeouts: ProcessingTimeout,
    configs: Map[String, LocalSvcAppConfig],
    parametersFor: String => SvcAppParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[ // TODO(i142): We should remove the CantonNode/CantonNodeBootstrap type requirements from
      // this trait.
      SvcAppNode,
      LocalSvcAppConfig,
      SvcAppParameters,
      SvcAppBootstrap,
    ](create, migrationsFactory, _timeouts, configs, parametersFor, _loggerFactory) {}
