// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.environment.DarResource
import com.google.protobuf.ByteString
import java.io.InputStream

sealed trait UploadablePackage {
  def packageId: String
  def resourcePath: String

  def inputStream(): InputStream = Option(
    getClass.getClassLoader.getResourceAsStream(resourcePath)
  ) match {
    case Some(is) => is
    case None =>
      throw new IllegalStateException(
        s"Failed to load [$resourcePath] from classpath"
      )
  }
}

object UploadablePackage {
  def fromResource(resource: DarResource): UploadablePackage = {
    new UploadablePackage {
      override val packageId = resource.packageId
      override val resourcePath = resource.path
    }
  }
  def fromByteString(name: String, bs: ByteString): UploadablePackage = {
    val pkgId = DarUtil.readPackageId(name, bs.newInput)
    (
      new UploadablePackage {
        override def packageId = pkgId
        override def resourcePath = name
        override def inputStream() = bs.newInput
      }
    )
  }
}
