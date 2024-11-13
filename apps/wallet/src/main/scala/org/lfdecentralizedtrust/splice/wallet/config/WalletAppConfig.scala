// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.config

import org.lfdecentralizedtrust.splice.config.{HttpClientConfig, NetworkAppClientConfig}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.RequireTypes.NonNegativeNumeric
import com.digitalasset.canton.topology.PartyId

case class WalletSynchronizerConfig(
    global: DomainAlias
)

// Inlined to avoid a dependency
case class WalletValidatorAppClientConfig(
    adminApi: NetworkAppClientConfig
) extends HttpClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}

case class WalletAppClientConfig(
    adminApi: NetworkAppClientConfig,
    ledgerApiUser: String,
) extends HttpClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}

final case class WalletSweepConfig(
    // The maximum balance in USD that should be kept in the wallet. When
    // exceeded a transfer offer for the difference between current balance and
    // minBalanceUsd will be made to the receiver.
    maxBalanceUsd: NonNegativeNumeric[BigDecimal],
    // The minimum balance in USD to keep in the wallet.
    minBalanceUsd: NonNegativeNumeric[BigDecimal],
    receiver: PartyId,
    // If set to true we use the transfer preapproval of the receiver to transfer
    // directly instead of creating a transfer offer.
    useTransferPreapproval: Boolean = false,
)

final case class AutoAcceptTransfersConfig(
    fromParties: Seq[PartyId] = Seq()
)
