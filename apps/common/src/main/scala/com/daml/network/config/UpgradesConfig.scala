// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.config

final case class UpgradesConfig(
    // whether to fail hard (on the client side) or just log when the app versions of the client and server do not match
    failOnVersionMismatch: Boolean = false
)
