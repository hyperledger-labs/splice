// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.config

import com.digitalasset.canton.config.FullClientConfig

// TODO(#19679) rename me perhaps; something ordering and/or scan and also bft sequencer is overloaded
case class BftSequencerConfig(
    migrationId: Long,
    sequencerAdminClient: FullClientConfig,
    p2pUrl: String,
)
