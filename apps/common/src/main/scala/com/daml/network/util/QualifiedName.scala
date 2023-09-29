package com.daml.network.util

import com.daml.ledger.javaapi.data.Identifier

/** To support upgrading templates are addressed
  * using their qualified name, ignoring the package id.
  */
final case class QualifiedName(moduleName: String, entityName: String) {
  override def toString = s"$moduleName:$entityName"
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
