package dev.guardrail.generators.scala.pekko

import cats.data.NonEmptyList
import cats.Monad
import dev.guardrail.{AuthImplementation, Target}
import dev.guardrail.core.{SupportDefinition, Tracker}
import dev.guardrail.generators.{CustomExtractionField, RenderedRoutes, TracingField}
import dev.guardrail.generators.scala.{
  CirceModelGenerator,
  CirceRefinedModelGenerator,
  ModelGeneratorType,
  ScalaLanguage,
}
import dev.guardrail.generators.scala.akkaHttp.{AkkaHttpHelper, AkkaHttpServerGenerator}
import dev.guardrail.generators.scala.akkaHttp.AkkaHttpVersion.V10_2
import dev.guardrail.generators.spi.{
  ModuleLoadResult,
  ProtocolGeneratorLoader,
  ServerGeneratorLoader,
}
import dev.guardrail.terms.{Responses, SecurityScheme}
import dev.guardrail.terms.protocol.StrictProtocolElems
import dev.guardrail.terms.server.{GenerateRouteMeta, SecurityExposure, ServerTerms}
import io.swagger.v3.oas.models.Operation

import scala.meta._
import scala.reflect.runtime.universe.typeTag

class PekkoServerGeneratorLoader extends ServerGeneratorLoader {
  type L = ScalaLanguage
  override def reified = typeTag[Target[ScalaLanguage]]
  val apply =
    ModuleLoadResult.forProduct2(
      ServerGeneratorLoader.label -> Seq(PekkoHttpVersion.mapping),
      ProtocolGeneratorLoader.label -> Seq(
        CirceModelGenerator.mapping,
        CirceRefinedModelGenerator.mapping.view.mapValues(_.toCirce).toMap,
      ),
    ) { (_, collectionVersion) =>
      PekkoHttpServerGenerator(collectionVersion)
    }
}

object PekkoHttpServerGenerator {

  val pekkoTransformer = new Transformer {
    override def apply(tree: Tree): Tree = tree match {
      case t @ Term.Name(n) if (n == "akka.stream") => Term.Name("pekko.stream")
      case node => super.apply(node)
    }
  }

  def apply(modelGeneratorType: ModelGeneratorType): ServerTerms[ScalaLanguage, Target] = {
    val akka = AkkaHttpServerGenerator(V10_2, modelGeneratorType)
    new ServerTerms[ScalaLanguage, Target] {
      val customExtractionTypeName: Type.Name = Type.Name("E")
      override def MonadF: Monad[Target] = akka.MonadF

      override def buildCustomExtractionFields(
          operation: Tracker[Operation],
          resourceName: List[String],
          customExtraction: Boolean,
      ): Target[Option[CustomExtractionField[ScalaLanguage]]] =
        akka.buildCustomExtractionFields(operation, resourceName, customExtraction)

      override def buildTracingFields(
          operation: Tracker[Operation],
          resourceName: List[String],
          tracing: Boolean,
      ): Target[Option[TracingField[ScalaLanguage]]] = akka.buildTracingFields(
        operation,
        resourceName,
        tracing,
      )

      override def generateRoutes(
          tracing: Boolean,
          resourceName: String,
          handlerName: String,
          basePath: Option[String],
          routes: List[GenerateRouteMeta[ScalaLanguage]],
          protocolElems: List[StrictProtocolElems[ScalaLanguage]],
          securitySchemes: Map[String, SecurityScheme[ScalaLanguage]],
          securityExposure: SecurityExposure,
          authImplementation: AuthImplementation,
      ): Target[RenderedRoutes[ScalaLanguage]] = akka.generateRoutes(
        tracing,
        resourceName,
        handlerName,
        basePath,
        routes,
        protocolElems,
        securitySchemes,
        securityExposure,
        authImplementation,
      )

      override def getExtraRouteParams(
          resourceName: String,
          customExtraction: Boolean,
          tracing: Boolean,
          authImplementation: AuthImplementation,
          securityExposure: SecurityExposure,
      ): Target[List[Term.Param]] = akka.getExtraRouteParams(
        resourceName,
        customExtraction,
        tracing,
        authImplementation,
        securityExposure,
      )

      override def generateResponseDefinitions(
          responseClsName: String,
          responses: Responses[ScalaLanguage],
          protocolElems: List[StrictProtocolElems[ScalaLanguage]],
      ): Target[List[Defn]] = akka.generateResponseDefinitions(
        responseClsName,
        responses,
        protocolElems,
      )

      override def generateSupportDefinitions(
          tracing: Boolean,
          securitySchemes: Map[String, SecurityScheme[ScalaLanguage]],
      ): Target[List[SupportDefinition[ScalaLanguage]]] = akka.generateSupportDefinitions(
        tracing,
        securitySchemes,
      )

      // should be easier to just change the resulting AST and replace anything coming from akka with pekko, but failed to make it work
      def renderClass(
          resourceName: String,
          handlerName: String,
          annotations: List[scala.meta.Mod.Annot],
          combinedRouteTerms: List[scala.meta.Stat],
          extraRouteParams: List[scala.meta.Term.Param],
          responseDefinitions: List[scala.meta.Defn],
          supportDefinitions: List[scala.meta.Defn],
          securitySchemesDefinitions: List[scala.meta.Defn],
          customExtraction: Boolean,
          authImplementation: AuthImplementation,
      ): Target[List[Defn]] =
        for {
          _ <- Target.log.debug(
            s"renderClass(${resourceName}, ${handlerName}, <combinedRouteTerms>, ${extraRouteParams})"
          )
          protocolImplicits <- AkkaHttpHelper.protocolImplicits(modelGeneratorType)
        } yield {
          val handlerType = {
            val baseHandlerType = Type.Name(handlerName)
            if (customExtraction) {
              t"${baseHandlerType}[${customExtractionTypeName}]"
            } else {
              baseHandlerType
            }
          }
          val typeParams = if (customExtraction) List(tparam"$customExtractionTypeName") else List()
          val routesParams = List(param"handler: $handlerType") ++ extraRouteParams
          val routeImplicits =
            List(param"implicit mat: org.apache.pekko.stream.Materializer") ++ protocolImplicits
          List(q"""
            object ${Term.Name(resourceName)} {
              ..${supportDefinitions};
              def routes[..${typeParams}](..${routesParams})(..$routeImplicits): Route = {
                ..${combinedRouteTerms}
              }

              ..${responseDefinitions}
            }
          """)
        }

      def getExtraImports(tracing: Boolean, supportPackage: NonEmptyList[String]) =
        for {
          _ <- Target.log.debug(s"getExtraImports(${tracing})")
        } yield List(
          if (tracing) Option(q"import org.apache.pekko.http.scaladsl.server.Directive1") else None,
          Option(q"import scala.language.higherKinds"),
        ).flatten

      override def renderHandler(
          handlerName: String,
          methodSigs: List[Decl.Def],
          handlerDefinitions: List[Stat],
          responseDefinitions: List[Defn],
          customExtraction: Boolean,
          authImplementation: AuthImplementation,
          securityExposure: SecurityExposure,
      ): Target[Defn] = akka.renderHandler(
        handlerName,
        methodSigs,
        handlerDefinitions,
        responseDefinitions,
        customExtraction,
        authImplementation,
        securityExposure,
      )

    }
  }

}
