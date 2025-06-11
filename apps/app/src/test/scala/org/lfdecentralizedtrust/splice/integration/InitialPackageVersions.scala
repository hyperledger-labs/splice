package org.lfdecentralizedtrust.splice.integration

import org.lfdecentralizedtrust.splice.environment.PackageResource

object InitialPackageVersions {
  private lazy val initialPackageVersionMap: Map[String, String] = {
    import cats.syntax.either.*, io.circe.parser.*
    val envVar = sys.env.getOrElse("INITIAL_PACKAGE_VERSIONS", "{}")
    decode[Map[String, String]](envVar).valueOr(err =>
      throw new IllegalArgumentException(
        s"Failed to decode initial package versions: $envVar, error: $err"
      )
    )
  }

  def initialPackageVersion(pkg: PackageResource): String =
    initialPackageVersionMap.getOrElse(
      pkg.bootstrap.metadata.name,
      pkg.bootstrap.metadata.version.toString,
    )
}
