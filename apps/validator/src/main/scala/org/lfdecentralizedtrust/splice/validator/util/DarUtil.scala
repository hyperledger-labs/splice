// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.util

import org.lfdecentralizedtrust.splice.util.UploadablePackage
import com.google.protobuf.ByteString

import java.io.InputStream

private[validator] object DarUtil {
  def readDar(
      name: String,
      inputStream: InputStream,
  ): (UploadablePackage, ByteString) = {
    val darFile = ByteString.readFrom(inputStream)
    (UploadablePackage.fromByteString(name, darFile), darFile)
  }
}
