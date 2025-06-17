// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import cats.syntax.either.*
import com.daml.ledger.api.v2.value as scalaValue
import com.daml.ledger.javaapi.data.Value
import com.daml.ledger.javaapi.data.codegen.{
  ContractCompanion,
  ContractId,
  InterfaceCompanion,
  ValueDecoder,
}
import org.lfdecentralizedtrust.splice.environment.DarResource
import com.digitalasset.canton.daml.lf.value.json.ApiCodecCompressed
import com.digitalasset.canton.ledger.api.util.LfEngineToApi
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ErrorUtil
import com.digitalasset.daml.lf.archive.{ArchivePayload, Dar, DarReader}
import com.digitalasset.daml.lf.data.Ref
import com.digitalasset.daml.lf.data.Ref.{DottedName, ModuleName, PackageId, QualifiedName}
import com.digitalasset.daml.lf.typesig
import io.circe.Json

import java.util.zip.ZipInputStream

abstract class TemplateJsonDecoder {
  def decodeTemplate[TCid <: ContractId[T], T](
      companion: ContractCompanion[?, TCid, T]
  )(json: Json): T

  def decodeInterface[ICid <: ContractId[Marker], Marker, View](
      companion: InterfaceCompanion[Marker, ICid, View]
  )(json: Json): View

  def decodeValue[T](
      valueDecoder: ValueDecoder[T],
      packageId: String,
      moduleName: String,
      entityName: String,
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
    val templateId = companion.getTemplateIdWithPackageId
    val lfIdentifier = Ref.Identifier.assertFromString(
      s"${templateId.getPackageId}:${templateId.getModuleName}:${templateId.getEntityName}"
    )

    decode(ContractCompanion.valueDecoder[T](companion), lfIdentifier, json)
  }

  override def decodeValue[T](
      valueDecoder: ValueDecoder[T],
      packageId: String,
      moduleName: String,
      entityName: String,
  )(json: Json): T = {

    val lfIdentifier = Ref.Identifier.assertFromString(
      s"${packageId}:${moduleName}:${entityName}"
    )

    decode(valueDecoder, lfIdentifier, json)
  }

  override def decodeInterface[ICid <: ContractId[Marker], Marker, View](
      companion: InterfaceCompanion[Marker, ICid, View]
  )(json: Json): View = {
    val viewType = packageSignatures(
      PackageId.assertFromString(companion.getTemplateIdWithPackageId.getPackageId)
    )
      .interfaces(
        QualifiedName(
          ModuleName.assertFromString(companion.getTemplateIdWithPackageId.getModuleName),
          DottedName.assertFromString(companion.getTemplateIdWithPackageId.getEntityName),
        )
      )
      .viewType
      .getOrElse(
        throw new IllegalStateException(
          s"Internal error: ${companion.getTemplateIdWithPackageId} does not have a view type"
        )
      )
    decode(companion.valueDecoder, viewType, json)
  }

  private def decode[TCid <: ContractId[T], T](
      decoder: ValueDecoder[T],
      entityName: Ref.TypeConId,
      json: Json,
  ): T = {
    val sprayJson = JsonUtil.circeJsonToSprayJsValue(json)
    val lfValue = ApiCodecCompressed.jsValueToApiValue(
      sprayJson,
      entityName,
      typeLookup,
    )
    val scalaApiValue = LfEngineToApi
      .lfValueToApiValue(verbose = false, lfValue)
      .valueOr(err =>
        ErrorUtil.invalidState(s"Unexpected failure converting lf value to API value: $err")
      )
    val javaApiValue = Value.fromProto(scalaValue.Value.toJavaProto(scalaApiValue))
    decoder.decode(javaApiValue)
  }
}

object ResourceTemplateDecoder {

  def loadPackageSignaturesFromResource(
      resource: DarResource
  ): Map[PackageId, typesig.PackageSignature] = {
    val inputStream = getClass.getClassLoader.getResourceAsStream(resource.path)
    val dar: Dar[ArchivePayload] = DarReader
      .readArchive(resource.path, new ZipInputStream(inputStream))
      .valueOr(e =>
        throw new IllegalArgumentException(s"Failed to read DAR at path ${resource.path}: $e")
      )
    dar.all.map(a => a.pkgId -> typesig.reader.SignatureReader.readPackageSignature(a)._2).toMap
  }

  def loadPackageSignaturesFromResources(
      resources: Seq[DarResource]
  ): Map[PackageId, typesig.PackageSignature] =
    resources.foldLeft(Map.empty[PackageId, typesig.PackageSignature]) { case (acc, p) =>
      acc ++ loadPackageSignaturesFromResource(p)
    }

}
