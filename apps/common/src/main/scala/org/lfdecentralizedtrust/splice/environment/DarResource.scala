package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.daml.lf.archive.Dar
import com.digitalasset.daml.lf.data.Ref.PackageId
import com.digitalasset.daml.lf.language.Ast.{Package, PackageMetadata}
import org.lfdecentralizedtrust.splice.util.DarUtil

import java.nio.file.Path
import scala.util.Using

final case class DarResource(
    path: String,
    packageId: String,
    metadata: PackageMetadata,
    dependencyPackageIds: Set[String],
)

/** All DARs for a given package
  */
final case class PackageResource(
    latest: DarResource, // latest package version
    minimumInitialization: DarResource, // The minimum version that can be used for initialization of a fresh network
    others: Seq[DarResource], // Other DARs for the same package
) {
  def getPackageIdWithVersion(version: String): Option[String] = {
    getDarResource(version).map(_.packageId)
  }

  def getDarResource(version: String): Option[DarResource] = {
    all.find(_.metadata.version.toString() == version)
  }

  def all = latest +: others
}

object DarResource {

  def apply(path: Path): DarResource = {
    val dar = DarUtil.readDar(path.toFile)
    apply(path.getFileName.toString, dar)
  }

  def apply(file: String): DarResource = {
    val input = getClass.getClassLoader.getResourceAsStream(file)
    if (input == null) {
      throw new IllegalArgumentException(s"Not found: $file")
    }
    val dar =
      Using.resource(input) { resourceStream =>
        DarUtil.readDar(file, resourceStream)
      }
    apply(file, dar)
  }

  private def apply(
      file: String,
      dar: Dar[(PackageId, Package)],
  ): DarResource = {
    DarResource(
      file,
      dar.main._1,
      dar.main._2.metadata,
      dar.dependencies.map(_._1).toSet,
    )
  }
}
