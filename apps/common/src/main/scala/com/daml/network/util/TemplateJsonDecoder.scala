package com.daml.network.util

import cats.syntax.either.*
import com.daml.ledger.api.v1.{value as scalaValue}
import com.daml.ledger.javaapi.data.codegen.{ContractCompanion, ContractId, ValueDecoder}
import com.daml.ledger.javaapi.data.Value
import com.daml.lf.archive.{ArchivePayload, Dar, DarReader}
import com.daml.lf.data.Ref.PackageId
import com.daml.lf.data.{ImmArray, Ref}
import com.daml.lf.typesig
import com.daml.lf.value.json.ApiCodecCompressed
import com.daml.platform.participant.util.LfEngineToApi
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ErrorUtil
import io.circe.Json
import spray.json.*

import java.util.zip.ZipInputStream

abstract class TemplateJsonDecoder {
  def decodeTemplate[TCid <: ContractId[T], T](
      companion: ContractCompanion[?, TCid, T]
  )(json: Json): T
}

/** Template decoder constructed from loading DAR files of the resources of our apps.
  */
class ResourceTemplateDecoder(
    packageSignatures: Map[PackageId, typesig.PackageSignature],
    override protected val loggerFactory: NamedLoggerFactory,
) extends TemplateJsonDecoder
    with NamedLogging {

  private implicit val elc: ErrorLoggingContext =
    ErrorLoggingContext(logger, Map.empty, TraceContext.empty)

  private def typeLookup(id: Ref.Identifier): Option[typesig.DefDataType.FWT] =
    for {
      iface <- packageSignatures.get(id.packageId)
      ifaceType <- iface.typeDecls.get(id.qualifiedName)
    } yield ifaceType.`type`

  override def decodeTemplate[TCid <: ContractId[T], T](
      companion: ContractCompanion[?, TCid, T]
  )(json: Json): T = {
    val sprayJson = json.noSpaces.parseJson
    val lfIdentifier = typesig.TypeConName(
      Ref.Identifier.assertFromString(
        s"${companion.TEMPLATE_ID.getPackageId}:${companion.TEMPLATE_ID.getModuleName}:${companion.TEMPLATE_ID.getEntityName}"
      )
    )
    val ty: typesig.Type = typesig.TypeCon(lfIdentifier, ImmArray.ImmArraySeq.empty)
    val lfValue = ApiCodecCompressed.jsValueToApiValue(
      sprayJson,
      ty,
      typeLookup(_),
    )
    // TODO(#2019) Switch to native JSON serialization in Java codegen once that has been added.
    val scalaApiValue = LfEngineToApi
      .lfValueToApiValue(false, lfValue)
      .valueOr(err =>
        ErrorUtil.invalidState(s"Unexpected failure converting lf value to API value: $err")
      )
    val javaApiValue = Value.fromProto(scalaValue.Value.toJavaProto(scalaApiValue))
    val decoder: ValueDecoder[T] = ContractCompanion.valueDecoder[T](companion)
    decoder.decode(javaApiValue)
  }
}

object ResourceTemplateDecoder {
  def loadPackageSignaturesFromResource(
      resourcePath: String
  ): Map[PackageId, typesig.PackageSignature] = {
    val inputStream = getClass.getClassLoader.getResourceAsStream(resourcePath)
    val dar: Dar[ArchivePayload] = DarReader
      .readArchive(resourcePath, new ZipInputStream(inputStream))
      .valueOr(e =>
        throw new IllegalArgumentException(s"Failed to read DAR at path $resourcePath: $e")
      )
    dar.all.map(a => a.pkgId -> typesig.reader.SignatureReader.readPackageSignature(a)._2).toMap
  }

  def loadPackageSignaturesFromResources(
      resourcePaths: Seq[String]
  ): Map[PackageId, typesig.PackageSignature] =
    resourcePaths.foldLeft(Map.empty[PackageId, typesig.PackageSignature]) { case (acc, p) =>
      acc ++ loadPackageSignaturesFromResource(p)
    }

}
