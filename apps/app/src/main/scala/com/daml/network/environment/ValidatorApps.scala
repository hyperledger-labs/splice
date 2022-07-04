package com.daml.network.environment

import com.daml.network.validator.config.{LocalValidatorConfig, ValidatorNodeParameters}
import com.daml.network.validator.{ValidatorNode, ValidatorNodeBootstrap}
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbMigrationsFactory

/** Validator app instances. */
class ValidatorApps(
    create: (String, LocalValidatorConfig) => ValidatorNodeBootstrap,
    migrationsFactory: DbMigrationsFactory,
    _timeouts: ProcessingTimeout,
    configs: Map[String, LocalValidatorConfig],
    parametersFor: String => ValidatorNodeParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[ // TODO(i142): We should remove the CantonNode/CantonNodeBootstrap type requirements from
      // this trait.
      ValidatorNode,
      LocalValidatorConfig,
      ValidatorNodeParameters,
      ValidatorNodeBootstrap,
    ](create, migrationsFactory, _timeouts, configs, parametersFor, _loggerFactory) {}
