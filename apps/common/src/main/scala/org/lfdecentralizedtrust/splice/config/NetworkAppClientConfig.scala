// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import org.apache.pekko.http.scaladsl.model.Uri
import com.digitalasset.canton.config.{KeepAliveClientConfig, TlsClientConfig}

/** A client configuration to a corresponding server configuration
  *  @see [[com.digitalasset.canton.config.ClientConfig]]
  */
case class NetworkAppClientConfig(
    url: Uri,
    tls: Option[TlsClientConfig] = None,
    keepAliveClient: Option[KeepAliveClientConfig] = Some(KeepAliveClientConfig()),
)

trait NetworkAppNodeConfig {
  def clientAdminApi: NetworkAppClientConfig
}
