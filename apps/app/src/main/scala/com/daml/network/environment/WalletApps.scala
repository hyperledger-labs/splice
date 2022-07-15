package com.daml.network.environment

import com.daml.network.config.SharedCoinAppParameters
import com.daml.network.wallet.config.LocalWalletAppConfig
import com.daml.network.wallet.{WalletApp, WalletAppBootstrap}
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbMigrationsFactory

/** Wallet app instances. */
class WalletApps(
    create: (String, LocalWalletAppConfig) => WalletAppBootstrap,
    migrationsFactory: DbMigrationsFactory,
    _timeouts: ProcessingTimeout,
    configs: Map[String, LocalWalletAppConfig],
    parametersFor: String => SharedCoinAppParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[ // TODO(i142): We should remove the CantonNode/CantonNodeBootstrap type requirements from
      // this trait.
      WalletApp,
      LocalWalletAppConfig,
      SharedCoinAppParameters,
      WalletAppBootstrap,
    ](create, migrationsFactory, _timeouts, configs, parametersFor, _loggerFactory) {}
