// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data.{Event, Identifier}
import org.lfdecentralizedtrust.splice.environment.DarResources

final case class QualifiedName(moduleName: String, entityName: String) {
  override def toString = s"$moduleName:$entityName"
}

final case class PackageQualifiedName(packageName: String, qualifiedName: QualifiedName) {
  override def toString = s"$packageName:${qualifiedName.toString}"
}

object PackageQualifiedName {
  def findFromResources(identifier: Identifier): Option[PackageQualifiedName] = {
    DarResources
      .lookupPackageId(identifier.getPackageId)
      .map { resource =>
        PackageQualifiedName(
          resource.metadata.name,
          QualifiedName(identifier),
        )
      }
  }

  def getFromResources(identifier: Identifier): PackageQualifiedName = {
    findFromResources(identifier)
      .getOrElse(throw new IllegalArgumentException(s"No package found for template $identifier"))
  }

  def fromEvent(event: Event): PackageQualifiedName = {
    PackageQualifiedName(
      event.getPackageName,
      QualifiedName(event.getTemplateId.getModuleName, event.getTemplateId.getEntityName),
    )
  }
}

object QualifiedName {
  def apply(identifier: Identifier): QualifiedName =
    QualifiedName(
      identifier.getModuleName,
      identifier.getEntityName,
    )

  def assertFromString(s: String): QualifiedName = {
    val segments = s.split(":")
    if (segments.length != 2) {
      throw new IllegalArgumentException(s"Expect qualified name with two identifiers but got $s")
    }
    QualifiedName(segments(0), segments(1))
  }
}
