// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import cats.syntax.either.*
import com.digitalasset.daml.lf.archive.{Dar, DarDecoder, DarParser}
import com.digitalasset.daml.lf.data.Ref.PackageId
import com.digitalasset.daml.lf.language.Ast.Package

import java.io.{File, FileInputStream, InputStream}
import java.util.zip.ZipInputStream
import scala.util.Using

object DarUtil {

  def readDar(file: File): Dar[(PackageId, Package)] =
    readDar(file.getName, new FileInputStream(file))

  def readDar(name: String, stream: InputStream): Dar[(PackageId, Package)] = {
    Using.resource(new ZipInputStream(stream)) { zipStream =>
      DarDecoder
        .readArchive(name, zipStream)
        .valueOr(err => throw new IllegalArgumentException(s"Failed to decode dar: $err"))
    }
  }

  def readPackageId(name: String, stream: InputStream): String = {
    Using.resource(new ZipInputStream(stream)) { zipStream =>
      val dar = DarParser
        .readArchive(name, zipStream)
        .valueOr(err => throw new IllegalArgumentException(s"Failed to read dar: $err"))
      dar.main.getHash
    }
  }
}
