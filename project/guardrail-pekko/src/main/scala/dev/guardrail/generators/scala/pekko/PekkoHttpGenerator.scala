package dev.guardrail.generators.scala.pekko

import cats.Monad
import dev.guardrail.Target
import dev.guardrail.generators.scala.{
  CirceModelGenerator,
  CirceRefinedModelGenerator,
  ModelGeneratorType,
  ScalaLanguage,
}
import dev.guardrail.generators.scala.akkaHttp.{AkkaHttpGenerator, AkkaHttpGeneratorLoader}
import dev.guardrail.generators.scala.akkaHttp.AkkaHttpVersion.V10_2
import dev.guardrail.generators.spi.{
  FrameworkGeneratorLoader,
  ModuleLoadResult,
  ProtocolGeneratorLoader,
}
import dev.guardrail.terms.framework.FrameworkTerms

import scala.meta._
import scala.reflect.runtime.universe.typeTag

class PekkoHttpGeneratorGuardrail extends FrameworkGeneratorLoader {

  type L = ScalaLanguage

  def reified = typeTag[Target[ScalaLanguage]]

  override val apply: Set[String] => ModuleLoadResult[FrameworkTerms[ScalaLanguage, Target]] =
    ModuleLoadResult.forProduct2(
      FrameworkGeneratorLoader.label -> Seq(PekkoHttpVersion.mapping),
      ProtocolGeneratorLoader.label -> Seq(
        CirceModelGenerator.mapping,
        CirceRefinedModelGenerator.mapping.view.mapValues(_.toCirce).toMap,
      ),
    ) { (_: PekkoHttpVersion, collectionVersion) =>
      PekkoHttpGenerator(collectionVersion)
    }
}

sealed abstract class PekkoHttpVersion(val value: String)

object PekkoHttpVersion {
  case object V10_1 extends PekkoHttpVersion("1.0.0")

  val mapping: _root_.scala.Predef.Map[
    _root_.scala.Predef.String,
    PekkoHttpVersion,
  ] = Map("pekko-http" -> V10_1)
}

object PekkoHttpGenerator {
  def apply(
      modelGeneratorType: ModelGeneratorType
  ): FrameworkTerms[ScalaLanguage, Target] =
    new PekkoHttpGeneratorAkkaWrapper(AkkaHttpGenerator(V10_2, modelGeneratorType))
}

class PekkoHttpGeneratorAkkaWrapper(akkaGenerator: FrameworkTerms[ScalaLanguage, Target])
    extends FrameworkTerms[ScalaLanguage, Target] {
  override def MonadF: Monad[Target] = akkaGenerator.MonadF

  override def getFrameworkImports(tracing: Boolean): Target[List[Import]] =
    Target.pure(
      List(
        q"import io.circe.Decoder",
        q"import org.apache.pekko.http.scaladsl.model._",
        q"import org.apache.pekko.http.scaladsl.model.headers.RawHeader",
        q"import org.apache.pekko.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller, FromEntityUnmarshaller, FromRequestUnmarshaller, FromStringUnmarshaller}",
        q"import org.apache.pekko.http.scaladsl.marshalling.{Marshal, Marshaller, Marshalling, ToEntityMarshaller, ToResponseMarshaller}",
        q"import org.apache.pekko.http.scaladsl.server.Directives._",
        q"import org.apache.pekko.http.scaladsl.server.{Directive, Directive0, Directive1, ExceptionHandler, MalformedFormFieldRejection, MalformedHeaderRejection, MissingFormFieldRejection, MalformedRequestContentRejection, Rejection, RejectionError, Route}",
        q"import org.apache.pekko.http.scaladsl.util.FastFuture",
        q"import org.apache.pekko.stream.{IOResult, Materializer}",
        q"import org.apache.pekko.stream.scaladsl.{FileIO, Keep, Sink, Source}",
        q"import org.apache.pekko.util.ByteString",
        q"import cats.{Functor, Id}",
        q"import cats.data.EitherT",
        q"import cats.implicits._",
        q"import scala.concurrent.{ExecutionContext, Future}",
        q"import scala.language.higherKinds",
        q"import scala.language.implicitConversions",
        q"import java.io.File",
        q"import java.security.MessageDigest",
        q"import java.util.concurrent.atomic.AtomicReference",
        q"import scala.util.{Failure, Success}",
      )
    )

  override def getFrameworkImplicits(): Target[Option[(Term.Name, Defn.Object)]] =
    akkaGenerator.getFrameworkImplicits()

  override def getFrameworkDefinitions(tracing: Boolean): Target[List[(Term.Name, List[Defn])]] =
    akkaGenerator.getFrameworkDefinitions(tracing)

  override def lookupStatusCode(key: String): Target[(Int, Term.Name)] =
    akkaGenerator.lookupStatusCode(key)

  override def fileType(format: Option[String]): Target[Type] = akkaGenerator.fileType(format)

  override def objectType(format: Option[String]): Target[Type] = akkaGenerator.objectType(format)
}
