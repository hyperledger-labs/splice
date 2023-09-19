package com.daml.network.environment

import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.directory.config.DirectoryAppBackendConfig
import com.daml.network.directory.{DirectoryApp, DirectoryAppBootstrap}
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbMigrationsFactory

/** Directory app instances. */
class DirectoryApps(
    create: (String, DirectoryAppBackendConfig) => DirectoryAppBootstrap,
    migrationsFactory: DbMigrationsFactory,
    _timeouts: ProcessingTimeout,
    configs: Map[String, DirectoryAppBackendConfig],
    parametersFor: String => SharedCNNodeAppParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[ // TODO(#736): We should remove the CantonNode/CantonNodeBootstrap type requirements from
      // this trait.
      DirectoryApp,
      DirectoryAppBackendConfig,
      SharedCNNodeAppParameters,
      DirectoryAppBootstrap,
    ](
      create,
      migrationsFactory,
      _timeouts,
      configs,
      parametersFor,
      startUpGroup = 0,
      _loggerFactory,
    ) {}
