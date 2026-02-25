..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



.. NOTE: add your upcoming release notes below this line. They are included in the `release_notes.rst`.

.. release-notes:: Upcoming

    - SV Participant

      - Participant pruning for super validators is now supported and
        recommended. Follow the :ref:`documentation
        <sv_participant_pruning>` for instructions on how to enable
        it.

    - Canton

      - JSON Ledger API OpenAPI/AsyncAPI Specification Updates
         We've corrected the OpenAPI and AsyncAPI specification files to properly reflect field requirements as defined in the Ledger API ``.proto`` files. Additionally, specification files now include the Canton version in their filenames (e.g., ``openapi-3.4.11.yaml``).

          - Impact and Migration
            If you regenerate client code from these updated specifications, your code may require changes due to corrected field optionality. You have two options:

            - **Keep using the old specification** - The JSON API server maintains backward compatibility with previous specification versions.
            - **Upgrade to the new specification** - Update your client code to handle the corrected optional/required fields:

              - **Java (OpenAPI Generator)**: Code compiles without changes, but static analysis tools may flag nullability differences.
              - **TypeScript**: Handle optional fields using ``!`` or ``??`` operators as needed.

            The JSON API server remains compatible with specification files from all 3.4.x versions (e.g., 3.4.9).

      - Sequencer Inspection Service
        A new service is available on the Admin API of the sequencer.
        It provides an RPC that allows to query for traffic summaries of sequenced events.
        Refer to the `traffic documentation <https://docs.digitalasset.com/subnet/3.4/howtos/operate/traffic.html>`__ for more details.

      - Minor Improvements
        - The ``sequencer-client.enable-amplification-improvements`` flag now defaults to `true`
        - New connection pool:

          - The connections gRPC channels are now correctly using the defined client keep-alive configuration. **If you had disabled
            the new connection pools because of issues before, please reenable them and report any issues.**
          - The connections gRPC channels are now configured with a ``maxInboundMessageSize`` set to ``MaxInt`` instead of the default 4MB (this will be improved in the future to use the dynamic synchronizer parameter ``maxRequestSize``).

        - Added ``keep-alive-without-calls`` and ``idle-timeout`` config values in the keep alive gRPC client configuration. See https://grpc.io/docs/guides/keepalive/#keepalive-configuration-specification for details.
          Note that when ``keep-alive-without-calls`` is enabled, ``permit-keep-alive-without-calls`` must be enabled on the server side, and ``permit-keep-alive-time`` adjusted to allow for a potentially higher frequency of keep alives coming from the client.

        .. important::

           `keep-alive-without-calls` can have a negative performance impact. Be cautious when turning it on, and in general prefer using ``idle-timeout`` when possible.

        ========================== ==============
        Config                     DefaultValue
        ========================== ==============
        keep-alive-without-calls   false
        idle-timeout               30 minutes
        ========================== ==============

        .. note::

           The value for ``idle-timeout`` should be set lower than timeouts in the network stack between client and server.
           In particular, check the idle timeout configuration of Load Balancers.
           Defaults for `AWS ALB <https://docs.aws.amazon.com/elasticloadbalancing/latest/application/application-load-balancers.html>`__, `AWS NLB <https://docs.aws.amazon.com/elasticloadbalancing/latest/network/update-idle-timeout.html>`__, `GCP <https://docs.cloud.google.com/load-balancing/docs/https/request-distribution#http-keepalive-timeout>`__.

        Example:

        Participant config:

        .. code-block:: python

            canton.participants.participant.sequencer-client.keep-alive-client.idle-timeout = 5 minutes
            # And / Or
            canton.participants.participant.sequencer-client.keep-alive-client.keep-alive-without-calls = true
            canton.participants.participant.sequencer-client.keep-alive-client.keep-alive-time = 6 minutes

        Sequencer config:

        .. code-block:: python

            # Must be enabled if keep-alive-without-calls is enabled on the client side
            canton.sequencers.sequencer.public-api.keep-alive-server.permit-keep-alive-without-calls = true
            canton.sequencers.sequencer.public-api.keep-alive-server.permit-keep-alive-time = 5 minutes

        - Lowered the log level of certain warnings to INFO level, if validation of the confirmation request or confirmation response failed due to a race with a topology change.

      - Bugfixes

        - Switched the gRPC service ``SequencerService.subscribe`` and ``SequencerService.downloadTopologyStateForInit`` to manual
          control flow, so that the sequencer doesn't crash with an ``OutOfMemoryError`` when responding to slow clients.
        - When the new connection pool is disabled using ``sequencer-client.use-new-connection-pool = false``, the health of the
          connection pool is no longer reported as a component in ``<node>.health.status`` (before the fix, the connection pool
          component would report a "Not initialized" status).
        - Fixed a race condition in mediator that could cause verdicts for invalid requests to not be emitted to ongoing inspection service streams.
          If you have disabled asynchronous processing using ``canton.mediators.mediator.mediator.asynchronous-processing = false``,
          you may remove this override now.

    - Scan txlog script

      - scan_txlog.py script has been deprecated. It will not be maintained moving forward, and will be removed completely in a future release.
        A user interested in understanding how to parse transactions from history is reffered to the :ref:`Reading and parsing transaction history involving Token Standard contracts <token_standard_usage_reading_tx_history>` section.
