package com.daml.network.util

import java.io.InputStream

trait UploadablePackage {
  def packageId: String
  def resourcePath: String

  def absolutePath(): String = {
    val url = getClass.getClassLoader.getResource(resourcePath)
    if (url != null) {
      url.getFile()
    } else {
      throw new IllegalStateException(
        s"Failed to determine absolute path of [$resourcePath] from classpath"
      )
    }
  }

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
