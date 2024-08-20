// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.ledger.javaapi.data.Identifier
import com.daml.network.environment.DarResources

/** To support upgrading templates are addressed
  * using their qualified name, ignoring the package id.
  */
final case class QualifiedName(moduleName: String, entityName: String) {
  override def toString = s"$moduleName:$entityName"
}

final case class PackageQualifiedName(packageName: String, qualifiedName: QualifiedName) {
  override def toString = s"$packageName:${qualifiedName.toString}"
}

object PackageQualifiedName {
  def apply(identifier: Identifier): PackageQualifiedName = {
    val resource = DarResources
      .lookupPackageId(identifier.getPackageId)
      .getOrElse(throw new IllegalArgumentException(s"No package found for template $identifier"))
    PackageQualifiedName(
      resource.metadata.name,
      QualifiedName(identifier),
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
