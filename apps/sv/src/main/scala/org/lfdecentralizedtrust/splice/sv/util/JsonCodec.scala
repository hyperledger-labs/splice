// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.util

import com.digitalasset.canton.topology.PartyId
import io.circe.*
import org.lfdecentralizedtrust.splice.util.{Codec, CodecCompanion}

private[util] object JsonCodec {

  implicit val partyDecoder: Decoder[PartyId] = codecDecoder(Codec.Party)
  implicit val partyEncoder: Encoder[PartyId] = Encoder.encodeString.contramap(_.toProtoPrimitive)

  def codecDecoder[Dec](codec: CodecCompanion[Dec])(implicit
      json: Decoder[codec.Enc]
  ): Decoder[Dec] =
    json.emap(codec.instance.decode)
}
