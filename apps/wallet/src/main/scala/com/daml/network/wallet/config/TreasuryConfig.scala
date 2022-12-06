package com.daml.network.wallet.config

/** Config for the treasury service for executing coin-balance-manipulating operations
  *
  * The service executes them in a sequential, batched fashion to avoid contention.
  *
  * For a target throughput of X op/s , the batch size should be chosen as daml-tx-commit-latency * X.
  * Choose a bufferSize of at least one batchSize to buffer new operations while the commit of the current batch is in
  * progress. If your arrival rate of commands fluctuates, then increase the buffer size to smoothen that fluctuation,
  * e.g, 2x or 3x the buffer size. Avoid large buffers though, as otherwise overload conditions are not resolved
  * quickly and operations unnecessarily time-out, as they stay in the buffer for a long time, instead of being aborted
  * quickly.
  *
  * @param batchSize how many coin-balance-manipulating operations to combine into a single batch.
  * @param bufferSize how many coin-balance-manipulating operations to buffer for creating the next batch.
  */
case class TreasuryConfig(
    batchSize: Int = 10,
    bufferSize: Int = 20,
)
