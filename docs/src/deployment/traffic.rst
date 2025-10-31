..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _traffic:

Synchronizer Traffic Fees
=========================

Synchronizer usage incurs operational costs for SVs.
For amortizing these costs, improving the incentives for operating an SV,
and making Denial Of Service attacks on the network unattractive,
synchronizer usage beyond a certain free `base rate` level is charged with so-called `synchronizer fees` paid in :term:`Canton Coin`.
For supporting this functionality, sequencers :ref:`keep track <traffic_accounting>` of available and consumed traffic
for each synchronizer member (most relevantly: for all validator participants).
Whenever both the free (base rate) and paid-for (extra) traffic are exhausted for a participant,
attempted writes are denied by the sequencer.

SVs, or more specifically SV participants and mediators,
have unlimited traffic and are therefore not subject to synchronizer fees themselves.
However, it is part of the responsibilities of SV operators to jointly ensure that the fee configuration of the Global Synchronizer
is sound and in line with the operational objectives of the network.

.. _traffic_accounting:

Traffic accounting; what counts as traffic?
-------------------------------------------

Sequencers keep track of the traffic used by validator participants.
`Traffic` in this context refers to all messages from participants that have to be `sequenced`, i.e.,
messages that the group of sequencers has to order, persist (up to a pruning interval),
and deliver to recipients (typically mediators and other participants).

Most prominently, traffic is used for Daml workflows as part of the
`Canton transaction processing protocol <https://docs.daml.com/canton/architecture/overview.html#transaction-processing-in-canton>`_.
This includes:

- Confirmation requests; sent when a participant initiates the committing of a ledger transaction.
- Confirmation responses; sent for participants that host stakeholders of a transaction.

Note that not only custom Daml workflows count towards traffic spend
but also automated "built-in" workflows such as rewards collection.

In addition to Daml workflow-related messages, participants also use traffic for, among other things:

- Submitting topology transactions,
  for example for allocating new parties or vetting newly uploaded DAR packages.
- Exchanging periodic :term:`ACS` commitments to ensure that they are in sync.

.. - Acknowledging the receipt of messages to sequencers.
.. - Requesting the current ledger time via ``TimeProof`` messages.

Internally, sequencers maintain various counters per validator participant that correspond to the more easily visible
:ref:`traffic parameters <traffic_parameters>` introduced next.
On a high level, sequencers keep track of:

- The available base rate traffic balance. The base rate is defined as a burst amount over a time window,
  so that even when fully depleted the available base rate traffic balance recovers fully after a ("window"-long) period of inactivity.
- The available extra traffic balance. This is traffic that has been :ref:`paid for <traffic_topup>`.
  The extra traffic balance is only consumed during times at which the base rate traffic balance is fully depleted;
  i.e., the base rate traffic balance is always consumed first.

When neither base rate nor extra traffic balance is available to a participant,
the sequencer will deny further attempts to submit messages for sequencing
until either the base rate traffic balance recovers or the extra traffic balance for the participant
gets :ref:`topped up <traffic_topup>`.
Note that as a safeguard against top-up deadlocks,
the validator app will under some conditions
:ref:`abort ledger submissions<error-below-reserved-traffic-amount>`
even if there is still some traffic balance left.
This only applies to submissions from the validator app itself!
If you run another application against the validator we recommend monitoring the traffic balance and pausing that app if you run out of traffic.

Traffic accounting is "by participant"; all parties hosted on the same participant share the same traffic balance.

.. _traffic_parameters:

Traffic parameters
------------------

The current synchronizer traffic parameters are recorded on the global ``AmuletRules`` contract
and can be obtained from Scan. You can obtain them via the Scan UI or by querying the Scan API using,
for example, this command (requires installing `jq <https://jqlang.org/>`_):

.. parsed-literal::

    curl -X POST --header "Content-Type: application/json" -d "{}" |gsf_scan_url|/api/scan/v0/amulet-rules | jq ".amulet_rules_update.contract.payload.configSchedule.initialValue.decentralizedSynchronizer.fees"

Above command will return a JSON object similar to the following:

.. code-block:: json

    "fees": {
      "baseRateTrafficLimits": {
        "burstAmount": "400000",
        "burstWindow": {
          "microseconds": "1200000000"
        }
      },
      "extraTrafficPrice": "60.0",
      "readVsWriteScalingFactor": "4",
      "minTopupAmount": "200000"
    }


This represents an encoded instance of the
``SynchronizerFeesConfig`` Daml data type.
See the :ref:`Daml API docs <type-splice-decentralizedsynchronizer-synchronizerfeesconfig-12142>`
for detailed information on these fields.
To give an overview here:

- ``baseRateTrafficLimits``: defines the free tier for synchronizer traffic.
  Validators can use up to ``burstAmount`` bytes of traffic within a ``burstWindow`` time window without incurring fees.
  The amount of available free traffic is restored periodically and will always reach its maximum (``burstAmount``)
  after a full ``burstWindow`` of complete inactivity.
  Note that even if no ledger submissions are being triggered, however,
  participants might still consume some traffic as part of normal operations
  (see :ref:`traffic_accounting` above).
- ``extraTrafficPrice``: the price of extra traffic beyond the free tier, denominated in USD per MB.
  The price is charged in :term:`CC` as per the current USD exchange rate.
  The exchange rate is determined by SVs via median voting and
  recorded on current ``OpenMiningRound`` contracts obtainable from Scan.
  For querying the current CC price in USD as per the currently open mining round,
  you can check the Scan UI or use the following command (requires installing `jq <https://jqlang.org/>`_):

  .. parsed-literal::

     curl -X POST --header "Content-Type: application/json" -d "{\"cached_open_mining_round_contract_ids\":[], \"cached_issuing_round_contract_ids\":[]}" |gsf_scan_url|/api/scan/v0/open-and-issuing-mining-rounds | jq ".open_mining_rounds | values[] | .contract.payload | {round, amuletPrice}"

- ``readVsWriteScalingFactor``: specifies the weight of additional traffic balance subtractions (from a sender's balance)
  for delivering a synchronizer message to each of its recipients.
  Delivering messages incurs actual costs for the SVs,
  even if this cost is much smaller than the cost of ordering and persisting messages.
  The ``readVsWriteScalingFactor`` is specified in basis points (parts per 10,000),
  i.e., a value of 1 means that for each 1000 bytes that need to be delivered to a recipient, 0.1 bytes of traffic will be charged.
  So for example:
  At a factor of 4, a 1 MB message with 10 recipients will draw
  ``1,000,000 * (1 + 10 * 0.004) = 1,040,000`` bytes from the sending participant's traffic balance.
- ``minTopupAmount``: the minimum amount of traffic that must be bought when buying extra traffic.
  Keeping this value reasonably high ensures that SVs can amortize the cost of performing the :ref:`top-up <traffic_topup>` operation,
  i.e., protects them from disproportionate overhead from processing very small top-ups.

Like all parts of the ``AmuletRulesConfig``, the ``SynchronizerFeesConfig`` is set by SVs via on-ledger voting, as part of DSO governance.
The :ref:`SV operations docs <sv-determining-traffic-parameters>` contain pointers for determining good values for some of these parameters.

.. _traffic_topup:

Traffic top-ups; how does one "buy" traffic?
--------------------------------------------

"Buying traffic" resolves around two main components:

1. On-ledger ``MemberTraffic`` contracts
   (:ref:`Daml API docs <type-splice-decentralizedsynchronizer-membertraffic-74049>`)
   that track each validator's traffic state and are updated (atomically) whenever CC is spent for buying traffic.
   Most importantly, on-ledger ``MemberTraffic`` contracts track the total amount of extra traffic purchased for that validator.
2. In-sequencer traffic state that tracks both available and spent traffic.
   SVs update the in-sequencer traffic state based on the ``MemberTraffic`` state they observe on ledger,
   thereby ensuring that paid traffic fees are translated into actual traffic balance increases.
   Sequencers also update the in-sequencer traffic state themselves, whenever traffic is consumed (see :ref:`traffic_accounting`).

The validator app contains built-in top-up automation that automatically buys traffic to meet preconfigured throughput needs.
In a nutshell, operators can configure a target throughput (per minimum top-up interval)
and the validator app will automatically buy extra traffic ensuring that
(1) sufficient traffic balance is available to sustain the configured target throughput, and
(2) exceeding the target throughput will not incur additional charges.
Additionally, to prevent top-up transactions from failing due to an already depleted traffic balance,
the validator app will
:ref:`abort ledger submissions if the balance has fallen below a predefined amount<error-below-reserved-traffic-amount>`.

For configuring the built-in top-up automation, please refer to the :ref:`Kubernetes validator deployment guide <helm_validator_topup>`
or the corresponding :ref:`Docker-compose one<compose_validator_topup>`.
Configuring alternative methods for buying traffic, e.g., using third-party services, exceeds the scope of this documentation.

.. _traffic_wasted:

Wasted traffic
--------------

`Wasted traffic` is defined as synchronizer events that have been sequenced but will not be delivered to their recipients.
For validators, which are subject to traffic fees,
wasted traffic implies that :ref:`traffic <traffic_accounting>` has been charged for a message that was ultimately not delivered.
Not all failed submissions result in wasted traffic:
wasted traffic only occurs whenever a synchronizer event is rejected after sequencing but before delivery.
Some level of wasted traffic is expected and unavoidable, due to factors such as:

- Submission request amplification.
  Participants that use BFT sequencer connections retry submission requests after a timeout to ensure speedy delivery in the face of nonresponsive sequencers;
  if processing was simply slower than usual but the sequencer was not faulty, the duplicate request counts as wasted traffic.
- Duplication of messages within the ordering layer, typically linked to transient networking issues or load spikes.
- Duplication of submissions on the participant/app side, for example when catching up after restoring from a backup or after some crashes.

Validator perspective
+++++++++++++++++++++

Validator operators are encouraged to investigate the causes of repeatedly failing submissions.
As stated above, not all failed submissions result in wasted traffic, and some wasted traffic is unavoidable.
Attention is warranted, however, if the rate of wasted traffic increases significantly at some point in time.

The Splice distribution contains a :ref:`Grafana dashboard <metrics_grafana_dashboards>` about `Synchronizer Fees (validator view)`,
to assist in monitoring traffic-related metrics.
The `Rejected Event Traffic` panel on this dashboard is especially relevant for determining the rate of wasted traffic.
(Hover on the ⓘ symbols in panel headers for precise descriptions of the shown data.)

SV perspective
++++++++++++++

SV operators are encouraged to monitor wasted traffic across all synchronizer members,
as reported for example by sequencer :ref:`metrics <metrics>`,
to detect cases where wasted traffic increases significantly and/or in a global manner.
The Splice distribution contains a :ref:`Grafana dashboard <metrics_grafana_dashboards>` about `Synchronizer Fees (SV view)` that can be helpful,
as well as an alert definition that focuses on validator participants.

Note that wasted traffic is less relevant for SVs themselves, as SV components have unlimited traffic.
Note also that SV mediators and sequencers waste traffic as part of their regular operation:
They heavily use aggregate submissions where sequencers collect messages from a group of senders and only deliver a single message per recipient once a threshold of individual submissions has been sequenced;
sequenced individual submissions beyond the aggregation threshold count as wasted traffic.
All that said, should an SV component suddenly exhibit a significant increase in wasted traffic,
this likely points to an actual issue that should be investigated.
