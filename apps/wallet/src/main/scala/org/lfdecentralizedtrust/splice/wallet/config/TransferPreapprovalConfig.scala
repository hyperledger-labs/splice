// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.config

import com.digitalasset.canton.config.NonNegativeFiniteDuration

final case class TransferPreapprovalConfig(
    // Each pre-approval is purchased for this length of time
    preapprovalLifetime: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofDays(90),
    // Automation will try to renew the pre-approval contract this much time before expiry
    renewalDuration: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofDays(30),
    // If set to false, acceptance of TransferPreapprovalProposal's is not deduplicated. This means that
    // if multiple proposals are created you will also get multiple TransferPreapprovals. This does not
    // break anything but you will pay extra fees and extra traffic for renewals.
    // In return, it allows the validator to batch acceptance of preapprovals which can improve throughput.
    proposalAcceptanceDeduplication: Boolean = true,
)
