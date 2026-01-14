..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

On each successful top-up, the validator app purchases a `top-up amount` of roughly ``targetThroughput * minTopupInterval`` bytes of traffic
(specific amount can vary due to rounding-up).
The ``minTopupInterval`` allows validator operators to control the upper-bound frequency at which automated top-ups happen.
If the top-up amount is below the synchronizer-wide ``minTopupAmount`` (see :ref:`traffic_parameters`),
``minTopupInterval`` is automatically stretched so that at least ``minTopupAmount`` bytes of traffic are
purchased while respecting the configured ``targetThroughput``.

The next top-up gets triggered when all of the following conditions are met:

- The available :ref:`extra traffic balance <traffic_accounting>` drops below the configured top-up amount
  (i.e., below ``targetThroughput * minTopupInterval``).
- At least ``minTopupInterval`` has elapsed since the last top-up.
- The validator has sufficient CC in its wallet to buy the top-up amount worth on traffic
  (except on DevNet, where the validator app will automatically tap enough coin to purchase traffic).


Validators receive a small amount of free traffic from the Super Validators, which suffices for submitting the
top-up transaction. However, if many other transactions are submitted, you may run into a situation where
you have exhausted also the free traffic, thus the validator cannot submit the top-up transaction.
The free traffic grant accumulates gradually and continuously. When no transactions are submitted, it
takes about twenty minutes for free traffic to accumulate to the maximum possible.
If you've consumed your traffic balance by submitting too many transactions without purchasing traffic,
pause your Validator node (validator app and participant) for twenty minutes to allow your free traffic
balance to accumulate.
