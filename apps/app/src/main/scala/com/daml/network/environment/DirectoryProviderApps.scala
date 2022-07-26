package com.daml.network.environment

import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.directory.provider.config.LocalDirectoryProviderAppConfig
import com.daml.network.directory.provider.{DirectoryProviderApp, DirectoryProviderAppBootstrap}
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbMigrationsFactory

/** DirectoryProvider app instances. */
class DirectoryProviderApps(
    create: (String, LocalDirectoryProviderAppConfig) => DirectoryProviderAppBootstrap,
    migrationsFactory: DbMigrationsFactory,
    _timeouts: ProcessingTimeout,
    configs: Map[String, LocalDirectoryProviderAppConfig],
    parametersFor: String => SharedCoinAppParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[ // TODO(i142): We should remove the CantonNode/CantonNodeBootstrap type requirements from
      // this trait.
      DirectoryProviderApp,
      LocalDirectoryProviderAppConfig,
      SharedCoinAppParameters,
      DirectoryProviderAppBootstrap,
    ](create, migrationsFactory, _timeouts, configs, parametersFor, _loggerFactory) {}
