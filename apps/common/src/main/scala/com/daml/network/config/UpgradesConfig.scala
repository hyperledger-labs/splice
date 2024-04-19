package com.daml.network.config

final case class UpgradesConfig(
    // whether to fail hard (on the client side) or just log when the app versions of the client and server do not match
    failOnVersionMismatch: Boolean = false
)
