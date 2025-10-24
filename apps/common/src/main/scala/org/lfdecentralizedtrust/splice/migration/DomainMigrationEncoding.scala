// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.migration

import com.google.protobuf.ByteString
import java.io.*
import java.time.Instant
import java.util.Base64
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
  import scala.util.Using

object DomainMigrationEncoding {
  private val base64Decoder = Base64.getDecoder()

  def encode(outputDirectory: Option[String], acsTimestamp: Instant, name: String, content: Seq[ByteString]): String = {
    outputDirectory match {
      case None =>
        Base64.getEncoder.encodeToString(ByteString.copyFrom(content.asJava).toByteArray)
      case Some(dir) =>
        val file = s"$dir/${acsTimestamp}-$name"
        Using.resource(
          new DataOutputStream(
            new BufferedOutputStream(
              new FileOutputStream(file)
            )
          )
        ) { dos =>
          writeChunks(dos, content)
        }
        file
    }
  }

  def decode(separateFiles: Option[Boolean], content: String): Seq[ByteString] = {
    if (separateFiles.getOrElse(false)) {
      Using.resource(
        new DataInputStream(
          new BufferedInputStream(
            new FileInputStream(content)
          )
        )
      )(readAllChunks)
    } else {
      Seq(ByteString.copyFrom(base64Decoder.decode(content)))
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Var", "org.wartremover.warts.While"))
  private def readAllChunks(
      dis: DataInputStream
  ): Seq[ByteString] = {
    val acc = ListBuffer.empty[ByteString]
    var eof = false
    while (!eof) {
      try {
        val length = dis.readInt()
        val chunk = new Array[Byte](length)
        dis.readFully(chunk)
        acc.addOne(ByteString.copyFrom(chunk))
      } catch {
        case _: EOFException =>
          eof = true
      }
    }
    acc.toSeq
  }

  private def writeChunks(dos: DataOutputStream, chunks: Seq[ByteString]): Unit = {
    chunks.foreach { chunk =>
      dos.writeInt(chunk.size)
      dos.write(chunk.toByteArray)
    }
  }
}
