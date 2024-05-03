package com.daml.network.wallet.config

import com.daml.network.config.{HttpCNNodeClientConfig, NetworkAppClientConfig}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.RequireTypes.NonNegativeNumeric

case class WalletSynchronizerConfig(
    global: DomainAlias
)

// Inlined to avoid a dependency
case class WalletValidatorAppClientConfig(
    adminApi: NetworkAppClientConfig
) extends HttpCNNodeClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}

case class WalletAppClientConfig(
    adminApi: NetworkAppClientConfig,
    ledgerApiUser: String,
) extends HttpCNNodeClientConfig {
  override def clientAdminApi: NetworkAppClientConfig = adminApi
}

final case class WalletSweepConfig(
    // The maximum balance in USD that should be kept in the wallet. When
    // exceeded a transfer offer for the difference between current balance and
    // minBalanceUsd will be made to the receiver.
    maxBalanceUsd: NonNegativeNumeric[BigDecimal],
    // The minimum balance in USD to keep in the wallet.
    minBalanceUsd: NonNegativeNumeric[BigDecimal],
    // TODO(#12126): use PartyId instead of String
    receiver: String,
)

final case class AutoAcceptTransfersConfig(
    // TODO(#12126): use PartyId instead of String
    fromParties: Seq[String] = Seq()
)
