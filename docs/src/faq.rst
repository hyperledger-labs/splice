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

    The read cost accounts for the cost of delivering the envelopes to all the recipients. It is computed as
    ``readCost = writeCost * #recipients * costMultiplier / 10_000``
    (`code <https://github.com/digital-asset/canton/blob/1f7d0513843ae791f870ac2caeb4402f73109f86/community/base/src/main/scala/com/digitalasset/canton/sequencing/traffic/EventCostCalculator.scala#L118-L123>`_).

  Is there an API to get the validator party ID?

    See `/v0/validator-user <https://github.com/hyperledger-labs/splice/blob/c712e75775a7d3229eff2a1837b06417a02b03f3/apps/validator/src/main/openapi/validator-internal.yaml#L14>`__.


Application Development
+++++++++++++++++++++++

JSON API
________

.. glossary::

  How can I fetch more than 200 entries for ACS through the JSON API?

    There is a server limit which is by default 200.  The configuration file can be updated as shown below where it increases the ``http-list-max-elements-limit`` to have a value of 1,000.

    .. code-block:: text

        canton {
          participants {
            participant1 {
              http-ledger-api {
                address = 0.0.0.0
                port = 10010
                port-file = "./json.port"
                path-prefix = "my-prefix"
                websocket-config {
                    http-list-max-elements-limit = 1000,
                    http-list-wait-time = 2s,
                }
                daml-definitions-service-enabled = true
              }
            }
          }
        }

    As per the information in :ref:`Adding ad-hoc configuration <configuration_ad_hoc>`,
    add an environment variable ``ADDITIONAL_CONFIG_JSON_LIMIT=canton.participants.participant.http-ledger-api.websocket-config { http-list-max-elements-limit = 1000, http-list-wait-time = 2s }`` to your Canton participant docker process.

    Then you can add an extra limit on query `(?limit=xyz)` to the request but the result will never exceed server limit.

    One alternative is the use the `websockets APIs <https://docs.digitalasset.com/build/3.3/reference/json-api/asyncapi.html>`__  which don't have a hard limit.

    Another alternative is to use the `PQS <https://docs.digitalasset.com/build/3.3/sdlc-howtos/applications/develop/pqs/index.html>`__
    which can simplify debugging via `Daml Shell <https://docs.digitalasset.com/build/3.3/sdlc-howtos/applications/develop/debug/daml-shell/index.html#contract-summaries>`__.

  How can I create a transaction with more than one root node?

    An example where this can occur is a transaction with one root for the marker creation and one root for the actual user command. This error message is indicative of
    trying a transaction with multiple roots:  ``Only single root transactions can currently be externally signed``.

    The recommended approach is to wrap the two calls in a little helper contract. Here is `an example <https://github.com/hyperledger-labs/splice/pull/1907/files#diff-90d0ed0955b3e59b9edec55e5191d155335bae39a258dbd029b53a4e53e15db3>`__.

  How can our application match registered public keys with their corresponding parties to identify the party associated with an onboarded user?

    There are several alternatives.

      * Using the Canton API, the `call listPartyToKeyMappingRequest <https://github.com/digital-asset/canton/blob/eeb56bc5d9779a7f918893b7a6b15e0b312a044e/community/base/src/main/protobuf/com/digitalasset/canton/topology/admin/v30/topology_manager_read_service.proto#L19>`__
        without a filter and then reverse the mapping, storing this locally (e.g., in a database) for fast access.
      * Make the mapping deterministic. Parties have format ``name::key_fingerprint`` where the fingerprint is computed from the public key. So if you always choose a deterministic name
        (e.g. "ledger", or another fingerprint), then you don't need to read the mapping at all because you can compute the fingerprint.
        Special consideration is needed here if you start using different keys for the "namespace root", which determines the fingerprint, the signing key, and/or a delegated "intermediary namespace key".
      * You can also filter by signed key through the ``filter_signed_key`` field in the ``base_query`` . If you use the fingerprint of the party, it should give you a decent filter.
        This can be used by any of the ``List***Request`` `APIs <https://github.com/digital-asset/canton/blob/eeb56bc5d9779a7f918893b7a6b15e0b312a044e/community/base/src/main/protobuf/com/digitalasset/canton/topology/admin/v30/topology_manager_read_service.proto#L14>`__,
        like ``ListPartyToKeyMapping``.

  How do I find the specifications for the latest API version for a Dev/Test/MainNet release?

    The Canton Network capabilities are always being enhanced so you need to use the latest API version specification.  The steps for the JSON API or gRPC API are similar.

      - For the JSON API's OpenAPI or AsyncAPI specifications, follow these steps:

        * Find the network's SDK version from the Splice docs. For example, DevNet's version information
          `is here <https://docs.dev.sync.global/app_dev/overview/version_information.html>`__.  For TestNet, substitute ``test`` in the URL.
        * Record the major and minor semver digits for the Canton version.  For example, if the snapshot is
          ``3.3.0-snapshot.20250827.16063.0.vdc9a8874`` then the important version information is ``3.3``.
        * Go to the open source `Canton Git repo <https://github.com/digital-asset/canton>`__.
        * Pick the appropriate release line by selecting the drop down that says `main` to expose
          the different branches that are available.  Then select the release line that has the same
          version found above.  In this example, the release line to select is ``release-line-3.3``.
        * This is the most up to date code for that release line.  So follow the path
          ``/canton/tree/main/community/ledger/ledger-json-api/src/test/resources/json-api-docs`` to the ``openapi.yaml`` and ``asyncapi.yaml`` files.

      - Another source for the JSON API's specifications is to retrieve them from a running Canton participant node.  A
        description of this is in the `Verification - download OpenAPI <https://docs.digitalasset.com/build/3.3/tutorials/json-api/canton_and_the_json_ledger_api.html#verification-download-openapi>`__
        section.  The specifications are available as:

        * For OpenAPI: ``http://<host>:<port>/docs/openapi``

        * For AsyncAPI:  ``http://<host>:<port>/docs/asyncapi``

      - For  the GRPC protobuf definitions, follow the same steps as above but change the last step to be:

        * Follow the path ``/community/ledger-api/src/main/protobuf/com/daml/ledger/api/v2`` to the ``proto`` files.

  Are there working examples of using the websocket version of the json api?

    There are examples avalable.  See `here <https://github.com/digital-asset/canton/tree/main/community/app/src/pack/examples/09-json-api>`__.
    This `file <https://github.com/digital-asset/canton/blob/main/community/app/src/pack/examples/09-json-api/wsacs.sh>`__ sends a request via websocket as
    part of a scenario that is described in the `README.md <https://github.com/digital-asset/canton/blob/main/community/app/src/pack/examples/09-json-api/README.md>`__.

  The ``v2/updates/trees`` JSON ledger api is deprecated so what is replacing it?

    A ``v2/updates`` is being added in 3.3 and will be available soon.  In the interim, you can use ``v2/updates/flat`` which has the same behavior as ``UpdateService.GetUpdates``.

Token Standard
______________

.. glossary::

  What would be the best practice to including the ``DAR`` file of token standard into my `daml` project for data-dependencies  to point to?

    * Copy the token standard dars from `the repo <https://github.com/hyperledger-labs/splice/tree/main/daml/dars>`__
      and check them into your own repo.
    * Make sure to only depend on ``splice-token-api-*`` packages for your main files. These are guaranteed to be stable.
    * Put your Daml script tests for your workflow into separate ``*-test`` daml
      projects as done `in splice <https://github.com/hyperledger-labs/splice/tree/b91cf9a77bad6c513658401a57db87d975f9a526/token-standard/splice-token-standard-test>`__.
      See the `daml.yaml <https://github.com/hyperledger-labs/splice/blob/b91cf9a77bad6c513658401a57db87d975f9a526/token-standard/splice-token-standard-test/daml.yaml>`__
      for details.

  Is there any open-source wallet implementation for canton coins?

    There's a wallet SDK `here <https://docs.digitalasset.com/integrate/devnet/index.html>`__ which is under rapid development.  No OSS UI yet though.
