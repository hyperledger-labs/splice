package org.lfdecentralizedtrust.splice.integration

import org.lfdecentralizedtrust.splice.environment.PackageResource

object InitialPackageVersions {
  private lazy val initialPackageVersionMap: Map[String, String] = {
    import cats.syntax.either.*, io.circe.parser.*
    val envVar = sys.env.get("INITIAL_PACKAGE_VERSIONS").filter(_.nonEmpty).getOrElse("{}")
    decode[Map[String, String]](envVar).valueOr(err =>
      throw new IllegalArgumentException(
        s"Failed to decode initial package versions: $envVar, error: $err"
      )
    )
  }

  def initialPackageVersion(pkg: PackageResource): String =
    initialPackageVersionMap.getOrElse(
      pkg.latest.metadata.name,
      pkg.latest.metadata.version.toString,
    )
}
