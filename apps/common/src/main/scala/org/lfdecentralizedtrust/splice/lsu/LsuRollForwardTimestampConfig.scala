// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.lsu

import better.files.*
import com.digitalasset.canton.data.CantonTimestamp
import pureconfig.generic.FieldCoproductHint
import pureconfig.generic.semiauto.*
import pureconfig.{ConfigReader, ConfigWriter}
import java.nio.file.Path

sealed abstract class LsuRollForwardTimestamp {
  def getTimestamp(): CantonTimestamp
}

final object LsuRollForwardTimestamp {
  final case class Timestamp(time: CantonTimestamp) extends LsuRollForwardTimestamp {
    def getTimestamp() = time
  }
  // This primarily exists for tests where in our setup, we need to fix the setup before anything runs
  // but we don't necessarily know the timestamp at that point.
  final case class TimestampFromFile(file: Path) extends LsuRollForwardTimestamp {
    def getTimestamp() = CantonTimestamp.assertFromString(File(file).contentAsString().trim())
  }

  implicit val configHint: FieldCoproductHint[LsuRollForwardTimestamp] =
    new FieldCoproductHint[LsuRollForwardTimestamp]("type")
  implicit val timestampReader: ConfigReader[Timestamp] = deriveReader[Timestamp]
  implicit val timestampFromFileReader: ConfigReader[TimestampFromFile] = deriveReader[TimestampFromFile]
  implicit val configReader: ConfigReader[LsuRollForwardTimestamp] = deriveReader[LsuRollForwardTimestamp]

  implicit val timestampWriter: ConfigWriter[Timestamp] = deriveWriter[Timestamp]
  implicit val timestampFromFileWriter: ConfigWriter[TimestampFromFile] = deriveWriter[TimestampFromFile]
  implicit val configWriter: ConfigWriter[LsuRollForwardTimestamp] = deriveWriter[LsuRollForwardTimestamp]
}
