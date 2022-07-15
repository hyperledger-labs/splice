package com.daml.network.util

import com.daml.ledger.api.refinements.ApiTypes
import com.digitalasset.network.CC.Coin.Coin

import java.io.InputStream

object CoinUtil {
  lazy val coinTemplateId: com.daml.ledger.api.v1.value.Identifier =
    ApiTypes.TemplateId.unwrap(Coin.id)
  lazy val packageId: String = coinTemplateId.packageId
  lazy val coinModuleName: String = coinTemplateId.moduleName
  lazy val coinEntityName: String = coinTemplateId.entityName

  // See `Compile / damlCodeGeneration` in build.sbt
  private val coinDarResourceName: String = "dar/canton-coin.dar"

  def coinDarInputStream(): InputStream =
    Option(
      getClass.getClassLoader.getResourceAsStream(coinDarResourceName)
    ) match {
      case Some(is) => is
      case None =>
        throw new IllegalStateException(
          s"Failed to load [$coinDarResourceName] from classpath"
        )
    }
}
