// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.config

import com.digitalasset.canton.config.NonNegativeFiniteDuration

final case class TransferPreapprovalConfig(
    // Each pre-approval is purchased for this length of time
    preapprovalLifetime: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofDays(90),
    // Automation will try to renew the pre-approval contract this much time before expiry
    renewalDuration: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofDays(30),
)
