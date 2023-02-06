package com.daml.network.config

import com.digitalasset.canton.DomainAlias

/** Domain config for apps that only need to have the global domain alias
  * configured.
  */
final case class GlobalOnlyDomainConfig(global: DomainAlias)
