package dev.guardrail.generators.scala

import dev.guardrail.Target
import dev.guardrail.generators.spi.ModuleMapperLoader

import scala.reflect.runtime.universe.typeTag

class PekkoScalaModuleMapper extends ModuleMapperLoader {
  type L = ScalaLanguage
  def reified = typeTag[Target[ScalaLanguage]]
  def apply(frameworkName: String): Option[Set[String]] = frameworkName match {
    case "pekko-http" => Some(Set("pekko-http", "circe"))
    case _ => None
  }
}
