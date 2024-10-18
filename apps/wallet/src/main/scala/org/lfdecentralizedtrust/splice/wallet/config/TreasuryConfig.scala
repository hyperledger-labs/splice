// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.config

import com.digitalasset.canton.config.NonNegativeFiniteDuration

/** Config for the treasury service for executing amulet-balance-manipulating operations
  *
  * The service executes them in a sequential, batched fashion to avoid contention.
  *
  * For a target throughput of X op/s , the batch size should be chosen as daml-tx-commit-latency * X.
  * Choose a queueSize of at least one batchSize to queue new operations while the commit of the current batch is in
  * progress. If your arrival rate of commands fluctuates, then increase the queue size to smoothen that fluctuation,
  * e.g, 2x or 3x the queue size. Avoid large queues though, as otherwise overload conditions are not resolved
  * quickly and operations unnecessarily time-out, as they stay in the queue for a long time, instead of being aborted
  * quickly.
  *
  * @param batchSize how many amulet-balance-manipulating operations to combine into a single batch.
  * @param queueSize how many amulet-balance-manipulating operations to queue for creating the next batch.
  */
case class TreasuryConfig(
    batchSize: Int = 10,
    queueSize: Int = 20,

    /** maximum amount of time to wait for grpc calls to complete for wallet operation
      *
      * This is used to set the deadline for grpc calls to the participant.
      * If the call takes longer than this, it will be cancelled and retried.
      * This is only intended for testing purposes.
      * TODO(#11501) block and unblock submissions on domain reconnect
      */
    grpcDeadline: Option[NonNegativeFiniteDuration] = None,
)
