// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.wallet.config

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
)
