..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _faq:

FAQ
===

Super Validators
++++++++++++++++

.. glossary::

  Where do I find all whitelisted node operators?

    All whitelisted SVs are listed in the `public global synchronizer configurations repository <https://github.com/global-synchronizer-foundation/configs>`_.

  Where do I generate a Validator Onboarding Token?

    SVs :ref:`generate a validator onboarding token <generate_onboarding_secret>` in the SV UI.

Validators
++++++++++

.. glossary::

  How long is my Validator Onboarding Token valid?

    They can be used for 48h for secrets created by SVs and 1h for secrets created through the DevNet self-onboarding endpoint. They are one-time use only.

  How do I check my validator liveness rewards?

    You can check your validator's liveness rewards by checking the transaction history and look for transaction that mints validator rewards.

    .. image:: images/transaction_history.png
        :width: 700
        :alt: Transaction history showing a validator reward transaction.

  How do I determine the traffic used for a specific transaction?

    1. Ensure you have ``DEBUG`` logs enabled in your participant configuration.
    2. Determine the trace-id of your command submission in your participant logs.
    3. Search for the ``DEBUG`` log lines containing ``EventCost`` and that ``trace-id``.
       There are typically two such log lines, due to how the `Canton protocol <https://docs.daml.com/canton/architecture/overview.html#transaction-processing-in-canton>`_ works.
       The first one is the cost of the submission of the confirmation request, and the second one
       is the cost for the submission of the confirmation response for the tx validation done by the participant node.

    For example, the following log line (pretty-printed by :ref:`lnav <where-to-find-logs>`) shows a command submission with trace-id ``1e2d6bf54d150e230fd0c7f348707bf6``
    that stems from tapping some Amulet.

    .. code-block:: text

      2025-07-10T14:39:43.155Z [⋮] INFO - c.d.c.p.a.s.c.CommandSubmissionServiceImpl:participant=aliceParticipant (1e2d6bf54d150e230fd0c7f348707bf6---) - Phase 1 started: Submitting commands for interpretation: Commands(
        commandId = org.lfdecentralizedtrust.splice.wallet.tap_92d9ffae4bd90068a15dad747559ed641572f057e3beb03f2a9b024f388bdc20,
        submissionId = 0a3e62aa-c4e7-44ba-ad8a-fdbd0e55b9e9,
        userId = alice_validator_user-b7e18d55,
        actAs = alice-validatorb7e18d55-1::12204bfd2aa7...,
        readAs = alice__wallet__user-b7e18d55__tc0::12204bfd2aa7...,
        submittedAt = 2025-07-10T14:39:43.154021Z,
        ledgerEffectiveTime = 1970-01-01T01:11:10Z,
        deduplicationPeriod = (duration=PT24H),
        synchronizerId = global-domain::12203755b6a7...,
        ...
      ).

    Searching for log lines matching the pattern ``aliceParticipant.*1e2d6bf54d150e230fd0c7f348707bf6.*EventCost`` will yield the following two log lines:

    .. code-block:: text

      2025-07-10T14:39:43.202Z [⋮] DEBUG - c.d.c.s.t.TrafficStateController:participant=aliceParticipant/synchronizerId=global-domain::12203755b6a7 (1e2d6bf54d150e230fd0c7f348707bf6---) - Computed following cost for submission request using topology at 1970-01-01T01:11:10.000177Z: EventCostDetails(
        cost multiplier = 4,
        group to members size = MediatorGroupRecipient(group = 0) -> 1,
        envelopes cost details = Seq(
          EnvelopeCostDetails(write cost = 1541, read cost = 0, final cost = 1541, recipients = MediatorGroupRecipient(group = 0)),
          EnvelopeCostDetails(
            write cost = 137,
            read cost = 0,
            final cost = 137,
            recipients = Seq(MemberRecipient(PAR::aliceValidator::12204bfd2aa7...), MediatorGroupRecipient(group = 0), MemberRecipient(PAR::sv1::1220c1e24991...))
          ),
          EnvelopeCostDetails(write cost = 1904, read cost = 0, final cost = 1904, recipients = MemberRecipient(PAR::aliceValidator::12204bfd2aa7...)),
          EnvelopeCostDetails(write cost = 2509, read cost = 2, final cost = 2511, recipients = Seq(MemberRecipient(PAR::aliceValidator::12204bfd2aa7...), MemberRecipient(PAR::sv1::1220c1e24991...)))
        ),
        event cost = 6093
      )
      2025-07-10T14:39:43.414Z [⋮] DEBUG - c.d.c.s.t.TrafficStateController:participant=aliceParticipant/synchronizerId=global-domain::12203755b6a7 (1e2d6bf54d150e230fd0c7f348707bf6---) - Computed following cost for submission request using topology at 1970-01-01T01:11:10.000179Z: EventCostDetails(
        cost multiplier = 4,
        group to members size = MediatorGroupRecipient(group = 0) -> 1,
        envelopes cost details = EnvelopeCostDetails(write cost = 651, read cost = 0, final cost = 651, recipients = MediatorGroupRecipient(group = 0)),
        event cost = 651
      )

    So the submission of the confirmation request cost 6093 bytes of traffic, and the submission of the confirmation response cost 651 bytes of traffic.

    The read cost accounts for the cost of delivering the envolopes to all the recipients. It is computed as
    ``readCost = writeCost * #recipients * costMultiplier / 10_000``
    (`code <https://github.com/digital-asset/canton/blob/1f7d0513843ae791f870ac2caeb4402f73109f86/community/base/src/main/scala/com/digitalasset/canton/sequencing/traffic/EventCostCalculator.scala#L118-L123>`_).


