package com.daml.network.util

import com.daml.ledger.api.refinements.ApiTypes
import com.digitalasset.network.CC.Coin.Coin

object CoinUtil {
  lazy val coinTemplateId: com.daml.ledger.api.v1.value.Identifier =
    ApiTypes.TemplateId.unwrap(Coin.id)
  lazy val packageId: String = coinTemplateId.packageId
  lazy val coinModuleName: String = coinTemplateId.moduleName
  lazy val coinEntityName: String = coinTemplateId.entityName
}
