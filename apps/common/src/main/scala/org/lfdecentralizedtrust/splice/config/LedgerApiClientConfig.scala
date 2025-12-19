// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.config.FullClientConfig

/** @param clientConfig Connection parameters
  * @param authConfig Auth tokens used by the app
  */
case class LedgerApiClientConfig(
    clientConfig: FullClientConfig,
    authConfig: AuthTokenSourceConfig,
)
