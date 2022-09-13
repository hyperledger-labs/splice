package com.daml.network.environment

import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.validator.config.LocalValidatorAppConfig
import com.daml.network.validator.{ValidatorAppBootstrap, ValidatorAppNode}
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbMigrationsFactory

/** Validator app instances. */
class ValidatorApps(
    create: (String, LocalValidatorAppConfig) => ValidatorAppBootstrap,
    migrationsFactory: DbMigrationsFactory,
    _timeouts: ProcessingTimeout,
    configs: Map[String, LocalValidatorAppConfig],
    parametersFor: String => SharedCoinAppParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[ // TODO(i736): We should remove the CantonNode/CantonNodeBootstrap type requirements from
      // this trait.
      ValidatorAppNode,
      LocalValidatorAppConfig,
      SharedCoinAppParameters,
      ValidatorAppBootstrap,
    ](create, migrationsFactory, _timeouts, configs, parametersFor, _loggerFactory) {}
