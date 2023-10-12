package com.daml.network.util

import cats.syntax.either.*
import com.daml.lf.archive.{Dar, DarDecoder}
import com.daml.lf.data.Ref.PackageId
import com.daml.lf.language.Ast.{Package, PackageMetadata}

import java.io.{File, FileInputStream, InputStream}
import java.util.zip.ZipInputStream
import scala.util.Using

object DarUtil {
  def readDarMetadata(resourcePath: String): PackageMetadata = {
    val resourceStream = getClass.getClassLoader.getResourceAsStream(resourcePath)
    if (resourceStream == null) {
      throw new IllegalArgumentException(s"Failed to parse resource: $resourcePath")
    }
    readDarMetadata(resourcePath, resourceStream)
  }

  def readDarMetadata(name: String, stream: InputStream): PackageMetadata = {
    val dar = readDar(name, stream)
    dar.main._2.metadata.getOrElse(
      throw new AssertionError(s"Package is missing metadata which is mandatory in LF >= 1.8")
    )
  }

  def readDar(file: File): Dar[(PackageId, Package)] =
    readDar(file.getName, new FileInputStream(file))

  def readDar(name: String, stream: InputStream): Dar[(PackageId, Package)] = {
    Using.resource(new ZipInputStream(stream)) { zipStream =>
      DarDecoder
        .readArchive(name, zipStream)
        .valueOr(err => throw new IllegalArgumentException(s"Failed to decode dar: $err"))
    }
  }
}
