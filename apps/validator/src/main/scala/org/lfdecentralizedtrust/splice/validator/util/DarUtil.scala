// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.util

import org.lfdecentralizedtrust.splice.util.UploadablePackage
import com.digitalasset.canton.crypto.{Hash, HashAlgorithm, HashOps, HashPurpose}
import com.google.protobuf.ByteString

import java.io.InputStream

private[validator] object DarUtil {
  private val hashOps = new HashOps {
    override def defaultHashAlgorithm: com.digitalasset.canton.crypto.HashAlgorithm.Sha256.type =
      HashAlgorithm.Sha256
  }

  def readDar(
      name: String,
      inputStream: InputStream,
  ): (UploadablePackage, Hash, ByteString) = {
    val darFile = ByteString.readFrom(inputStream)
    val darHash = hashOps.digest(HashPurpose.DarIdentifier, darFile)
    (UploadablePackage.fromByteString(name, darFile), darHash, darFile)
  }
}
