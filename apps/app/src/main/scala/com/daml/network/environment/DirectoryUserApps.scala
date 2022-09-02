package com.daml.network.environment

import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.directory.user.config.LocalDirectoryUserAppConfig
import com.daml.network.directory.user.{DirectoryUserApp, DirectoryUserAppBootstrap}
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbMigrationsFactory

/** DirectoryUser app instances. */
class DirectoryUserApps(
    create: (String, LocalDirectoryUserAppConfig) => DirectoryUserAppBootstrap,
    migrationsFactory: DbMigrationsFactory,
    _timeouts: ProcessingTimeout,
    configs: Map[String, LocalDirectoryUserAppConfig],
    parametersFor: String => SharedCoinAppParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[ // TODO(Arne): We should remove the CantonNode/CantonNodeBootstrap type requirements from
      // this trait.
      DirectoryUserApp,
      LocalDirectoryUserAppConfig,
      SharedCoinAppParameters,
      DirectoryUserAppBootstrap,
    ](create, migrationsFactory, _timeouts, configs, parametersFor, _loggerFactory) {}
