// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

final case class S3Config(
    endpoint: String,
    bucketName: String,
    region: String,
    accessKeyId: String,
    secretAccessKey: String,
)
object S3Config {
  def hideConfidential(config: S3Config): S3Config = {
    val hidden = "****"
    S3Config(
      config.endpoint,
      config.bucketName,
      config.region,
      config.accessKeyId,
      secretAccessKey = hidden,
    )
  }
}
