package dev.guardrail.generators.scala.pekko

import dev.guardrail.generators.scala.{
  CirceModelGenerator,
  CirceRefinedModelGenerator,
  ScalaLanguage,
}
import dev.guardrail.generators.spi.{
  ClientGeneratorLoader,
  ModuleLoadResult,
  ProtocolGeneratorLoader,
}
import dev.guardrail.Target
import dev.guardrail.generators.scala.akkaHttp.AkkaHttpClientGenerator

import scala.reflect.runtime.universe.typeTag

class PekkoFrameworkGeneratorLoader extends ClientGeneratorLoader {

  type L = ScalaLanguage

  def reified = typeTag[Target[ScalaLanguage]]

  override val apply = ModuleLoadResult.forProduct2(
    ClientGeneratorLoader.label -> Seq(PekkoHttpVersion.mapping),
    ProtocolGeneratorLoader.label -> Seq(
      CirceModelGenerator.mapping,
      CirceRefinedModelGenerator.mapping.view.mapValues(_.toCirce).toMap,
    ),
  ) { (_, collectionVersion) =>
    AkkaHttpClientGenerator(collectionVersion)
  }

}
