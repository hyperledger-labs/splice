package com.daml.network.validator.util

import cats.syntax.either.*
import com.daml.lf.archive.DarParser
import com.daml.network.util.UploadablePackage
import com.digitalasset.canton.crypto.{Hash, HashAlgorithm, HashOps, HashPurpose}
import com.google.protobuf.ByteString

import java.io.InputStream
import java.util.zip.ZipInputStream

private[validator] object DarUtil {
  private val hashOps = new HashOps {
    override def defaultHashAlgorithm = HashAlgorithm.Sha256
  }

  def readDar(
      name: String,
      inputStream: InputStream,
  ): (UploadablePackage, Hash, ByteString) = {
    val darFile = ByteString.readFrom(inputStream)
    val pkgId = DarParser
      .readArchive(name, new ZipInputStream(darFile.newInput))
      .valueOr(err => throw new IllegalArgumentException(s"Failed to decode dar: $err"))
      .main
      .getHash
    val darHash = hashOps.digest(HashPurpose.DarIdentifier, darFile)
    (
      new UploadablePackage {
        override def packageId = pkgId
        override def resourcePath = name
        override def inputStream() = darFile.newInput
      },
      darHash,
      darFile,
    )
  }
}
