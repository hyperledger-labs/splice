// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.{SvApp, SvAppBootstrap}
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbMigrationsFactory

/** SV app instances. */
class SvApps(
    create: (String, SvAppBackendConfig) => SvAppBootstrap,
    migrationsFactory: DbMigrationsFactory,
    _timeouts: ProcessingTimeout,
    configs: Map[String, SvAppBackendConfig],
    parametersFor: String => SharedSpliceAppParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[ // TODO(#736): We should remove the CantonNode/CantonNodeBootstrap type requirements from
      // this trait.
      SvApp,
      SvAppBackendConfig,
      SharedSpliceAppParameters,
      SvAppBootstrap,
    ](
      create,
      migrationsFactory,
      _timeouts,
      configs,
      parametersFor,
      startUpGroup = 0,
      _loggerFactory,
    ) {}
