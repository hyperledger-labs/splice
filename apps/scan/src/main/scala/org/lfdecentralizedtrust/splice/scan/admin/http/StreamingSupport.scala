// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import org.apache.pekko.http.scaladsl.marshalling.Marshaller
import org.apache.pekko.http.scaladsl.model.ResponseEntity
import io.circe.Decoder

object StreamingSupport {
//  // Guardrail maps 'type: string, format: binary' to Source[ByteString, Any]
//  // This implicit satisfies the requirement for a "Decoder"
//  implicit val octetStreamUnmarshaller: Unmarshaller[HttpEntity, Source[ByteString, Any]] =
//    Unmarshaller.withMaterializer { _ => implicit mat => entity =>
//      scala.concurrent.Future.successful(entity.dataBytes)
//    }
//
//  implicit val octetStreamMarshaller: ToEntityMarshaller[Source[ByteString, Any]] =
//    Marshaller.withFixedContentType(ContentTypes.`application/octet-stream`) { source =>
//      HttpEntity(ContentTypes.`application/octet-stream`, source)
//    }
//
implicit val responseEntityDecoder: Decoder[ResponseEntity] =
  Decoder.failedWithMessage("ResponseEntity cannot be decoded via Circe; use Pekko Unmarshallers.")

  implicit val httpEntityMarshaller: Marshaller[ResponseEntity, ResponseEntity] =
    Marshaller.opaque(identity)
}
