// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import org.lfdecentralizedtrust.splice.config.SharedSpliceAppParameters
import org.lfdecentralizedtrust.splice.scan.{ScanApp, ScanAppBootstrap}
import org.lfdecentralizedtrust.splice.scan.config.ScanAppBackendConfig
import com.digitalasset.canton.concurrent.ExecutionContextIdlenessExecutorService
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.environment.ManagedNodes
import com.digitalasset.canton.logging.NamedLoggerFactory

/** Scan app instances. */
class ScanApps(
    create: (String, ScanAppBackendConfig) => ScanAppBootstrap,
    _timeouts: ProcessingTimeout,
    configs: Map[String, ScanAppBackendConfig],
    parametersFor: String => SharedSpliceAppParameters,
    _loggerFactory: NamedLoggerFactory,
)(implicit
    protected val executionContext: ExecutionContextIdlenessExecutorService
) extends ManagedNodes[ // TODO(DACH-NY/canton-network-node#736): We should remove the CantonNode/CantonNodeBootstrap type requirements from
      // this trait.
      ScanApp,
      ScanAppBackendConfig,
      SharedSpliceAppParameters,
      ScanAppBootstrap,
    ](
      create,
      _timeouts,
      configs,
      parametersFor,
      startUpGroup = 0,
      _loggerFactory,
    ) {}
