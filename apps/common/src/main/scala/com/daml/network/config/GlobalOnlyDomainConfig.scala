package com.daml.network.config

/** Domain config for apps that only need to have the global domain alias
  * configured.
  */
final case class GlobalOnlyDomainConfig(global: DomainConfig)
