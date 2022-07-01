package com.daml.network

import com.daml.network.config.CoinConfig

package object integration {
  type CoinConfigTransform = CoinConfig => CoinConfig

}
