// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store.events

import com.daml.ledger.javaapi.data.CreatedEvent

/*
Wrapper around the created event to include the event id.
 */
case class SpliceCreatedEvent(
    eventId: String,
    event: CreatedEvent,
)
