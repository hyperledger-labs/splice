// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.store

import cats.data.OptionT
import com.digitalasset.canton.tracing.TraceContext
import io.circe.{Decoder, Encoder}
import scala.concurrent.{ExecutionContext, Future}
import io.circe.syntax.*

final case class ScanUrlInternalConfig(
    svName: String,
    url: String,
)

class ValidatorConfigProvider(config: ValidatorInternalStore) {

  private val scanInternalConfigKey = "validator_scan_internal_config_key"

  implicit val scanUrlEncoder: Encoder[ScanUrlInternalConfig] =
    Encoder.forProduct2("svName", "url")(s => (s.svName, s.url))
  implicit val scanUrlDecoder: Decoder[ScanUrlInternalConfig] =
    Decoder.forProduct2("svName", "url")(ScanUrlInternalConfig.apply)

  final def setScanUrlInternalConfig(
      value: Seq[ScanUrlInternalConfig]
  )(implicit tc: TraceContext): Future[Unit] = {
    config.setConfig(scanInternalConfigKey, value.asJson)
  }

  final def getScanUrlInternalConfig(
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): OptionT[Future, Seq[ScanUrlInternalConfig]] = {

    OptionT(
      config
        .getConfig(scanInternalConfigKey)
        .map { json =>
          json
            .as[Seq[ScanUrlInternalConfig]]
            .fold(
              failure =>
                throw new RuntimeException(
                  s"FATAL: Corrupt configuration data found for $scanInternalConfigKey. Cannot decode.",
                  failure,
                ),
              success => Some(success),
            )
        }
        .getOrElse(None)
    )
  }
}
