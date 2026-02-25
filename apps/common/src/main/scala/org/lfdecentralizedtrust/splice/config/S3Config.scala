package org.lfdecentralizedtrust.splice.config

final case class S3Config(
    endpoint: String,
    bucketName: String,
    region: String,
    accessKeyId: String,
    secretAccessKey: String,
)
