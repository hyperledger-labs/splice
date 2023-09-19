package com.daml.network.environment

import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.validator.{ValidatorApp, ValidatorAppBootstrap}
import com.daml.network.validator.config.ValidatorAppBackendConfig
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbMigrationsFactory

/** Validator app instances. */
class ValidatorApps(
    create: (String, ValidatorAppBackendConfig) => ValidatorAppBootstrap,
    migrationsFactory: DbMigrationsFactory,
    _timeouts: ProcessingTimeout,
    configs: Map[String, ValidatorAppBackendConfig],
    parametersFor: String => SharedCNNodeAppParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[ // TODO(#736): We should remove the CantonNode/CantonNodeBootstrap type requirements from
      // this trait.
      ValidatorApp,
      ValidatorAppBackendConfig,
      SharedCNNodeAppParameters,
      ValidatorAppBootstrap,
    ](
      create,
      migrationsFactory,
      _timeouts,
      configs,
      parametersFor,
      startUpGroup = 0,
      _loggerFactory,
    ) {}
