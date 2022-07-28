package com.daml.network.util

import java.io.InputStream

trait UploadablePackage {
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
