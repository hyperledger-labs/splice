package com.daml.network.environment

import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.scan.config.LocalScanAppConfig
import com.daml.network.scan.{ScanApp, ScanAppBootstrap}
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbMigrationsFactory

/** Scan app instances. */
class ScanApps(
    create: (String, LocalScanAppConfig) => ScanAppBootstrap,
    migrationsFactory: DbMigrationsFactory,
    _timeouts: ProcessingTimeout,
    configs: Map[String, LocalScanAppConfig],
    parametersFor: String => SharedCoinAppParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[ // TODO(#736): We should remove the CantonNode/CantonNodeBootstrap type requirements from
      // this trait.
      ScanApp,
      LocalScanAppConfig,
      SharedCoinAppParameters,
      ScanAppBootstrap,
    ](create, migrationsFactory, _timeouts, configs, parametersFor, _loggerFactory) {}
