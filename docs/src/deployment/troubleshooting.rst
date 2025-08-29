..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _troubleshooting:

Troubleshooting
===============

This section provides an overview of which information can be collected to debug issues in a Canton Network node.

.. toctree::

    console_access.rst

.. _where-to-find-logs:

Where to find logs
------------------

**When launched locally**, splice-node will create a ``log/`` directory located at the root of the repository and log into ``canton_network.log``.
Canton logs into ``canton.log``.

.. note::
    The default log level, initially set to Debug, can be changed using the ``--log-level-canton`` flag, for example: ``splice-node --config "${OUTPUT_CONFIG}" --log-level-canton=DEBUG ...``

**When the node is launched in a kubernetes cluster**, we recommend to setup a log collector so that you can capture logs of at least the last day. For now, the default log level is set to Debug.

We recommend to use ``lnav`` to read the logs. A guideline is provided in `this documentation <https://github.com/hyperledger-labs/splice/blob/main/TESTING.md#setting-up-lnav-to-inspect-canton-and-cometbft-logs>`_.

.. note::
    Logging in kubernetes (note that this only provides logs for a limited timeframe):

    - ``kubectl describe pod <pod-name>`` to get a detailed status of the given pod,
    - ``kubectl logs <pod-name> -n <namespace-name>`` or ``kubectl logs -l app=<app-name> -n <namespace-name> --tail=-1`` to get logs for a given pod in a given namespace.

Debugging issues in Web UIs
---------------------------

When facing an issue related to connectivity problems, if you are using chrome or firefox based browser:

1. Go to the right of the address bar in your browser, go on the settings and then ``More tools -> Developer tools`` (or by hitting Ctrl + Shift + i). Your browser developer tools window opens.

2. Click the ``Network`` tab.

3. Enable the ``Preserve or Persist log`` check box.

4. While the console remains open, **reproduce the issue you want to report**.

5. Once you are done, right-click and select ``Save all as HAR``

6. Name the file with the description of your issue and save it.

Once you are done reiterate 2-6 by clicking the ``Console`` tab instead of the ``Network`` tab.

Configurations
--------------

Another thing which is often quite helpful to diagnose issue is to collect all configurations.

- The application configuration files when running locally,
- The helm values when using the helm charts (``helm get values -n <namespace> <chartname>``),
- The environment variables when using the docker container but not the helm charts.

In addition to that also check the version you are using and the network (dev/test/mainnet) you are running against.

Common Error Messages
---------------------

.. _error-below-reserved-traffic-amount:

Traffic balance below reserved amount
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

A log of the form shown below indicates that your validator app
has not been able to :ref:`purchase any traffic <traffic_topup>`. The validator blocks
transactions not required to purchase more traffic once the purchased
traffic balance falls below a given number to
avoid issues where the validator locks itself out by not having enough
traffic to complete a traffic purchase. Check the logs for
``TopupMemberTrafficTrigger`` to find possible causes.

If you only want to rely on free traffic and do not want to purchase any extra traffic, remove
the validator top-up config.

.. code:: text

    ABORTED: Traffic balance below reserved traffic amount (0 < 200000)

.. _error-insufficient-funds:

Insufficient funds to buy configured traffic amount
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

A log of the form shown below indicates that your validator app
attempted to :ref:`purchase traffic <traffic_topup>` but does not have enough in the wallet
of the validator operator party. This is common on TestNet and MainNet
for new nodes as they start out with a balance of 0 and only slowly
accrue CC through validator liveness rewards. So often this just
requires waiting until enough CC has accrued. Alternatively, an
existing node with a CC balance can transfer CC to you to increase your balance.

If you only want to rely on free traffic and do not want to purchase any extra traffic, remove
the validator top-up config.

.. code:: text

    Insufficient funds to buy configured traffic amount. Please ensure that the validator’s wallet has enough amulets to purchase 1.9998 MB of traffic to continue healthy operation.

Gave up getting app version
~~~~~~~~~~~~~~~~~~~~~~~~~~~

A log of the form below can often indicate that you used a scan URL in
a place where an SV URL was expected or the other way around. Note the
mismatch between the prefix ``https://scan.`` and the path ``/api/sv``
at the end.

In a docker-compose setup, verify the URL passed to ``-s`` which should be a SV URL or ``svSponsorAddress`` for the helm deployment.

.. code:: text

    2025-02-11T10:16:13.098Z [⋮] ERROR - o.l.s.v.ValidatorSvConnection:validator=validator_backend (7427be2620676fce8a464eee769eb1d8-app_version-2d71c55f5ecd731b-793d382fa2d6ce14) - Gave up getting 'app version of https://scan.sv-2.dev.global.canton.network.digitalasset.com/api/sv' org.apache.pekko.http.scaladsl.unmarshalling.Unmarshaller$UnsupportedContentTypeException: Unsupported Content-Type [Some(text/html)], supported: application/json

UNAUTHENTICATED errors in validator, sv and scan app
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

A log of the form below in the SV, scan or validator app logs
indicates an authenitcation error on the connection to the
participant. Check the participant logs which will contain more
details on why the request got rejected.

.. code:: text

    2025-02-14T11:32:00.304Z [⋮] INFO - o.l.s.v.ValidatorApp:validator=validator_backend (50836441bf579035d64a56f776566cbf) - The operation 'Get user 7D95xiEUxju4IUXFQgyUrwHMMuZO0g2F@clients' failed with a retryable error (full stack trace omitted): UNAUTHENTICATED: An error occurred. Please contact the operator and inquire about the request efd009557dec03da74dd29b723949cd6 with tid efd009557dec03da74dd29b723949cd6

Node has identity X, but identifier Y was expected
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

A log of the form below in the validator or SV app indicates that you
tried to change the identifier used for your participant (or for SVs
sequencer, mediator) after it was already initialized.  Note that for
validators the node identifier defaults to your ``validatorPartyHint``
so changing that also produces this error. For SVs it defaults to the
SV name.  If this is a new node, the easiest option is to reset your
node by dropping the respective databases of the participant and
validator or for SVs sequencer, participant, mediator, validator, sv
and scan app. After you dropped the databases bring up your node
again.

.. warning:: This deletes all data on your node and you cannot
             recover it. Only run this on fresh nodes that never
             successfully initialized.


If this is not a new node, change
the values back to what you had before.

.. code:: text

    │Caused by: io.grpc.StatusRuntimeException: INTERNAL: Node has identity a-b-c-1::122098ffcd99..., but identifier a-b-1 was expected.                │

MemberDisabled error when connecting to sequencer
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

A log of the form below in your participant indicates that it has been down longer
than the 30 day sequencer pruning window so the SVs have disabled it.
Any attempts to connect will fail with the same error.
You can recover your CC balance by spinning up a new node via :ref:`validator_reonboard`.


.. code:: text

    2025-04-16T08:18:06.451Z [⋮] DEBUG - c.d.c.s.c.t.GrpcSequencerSubscription:participant=participant/domainId=global-domain::12206d339948/sequencerAlias=Some-Alias (---) - Completed subscription with Success(GrpcSubscriptionError(Request failed for sequencer.
      GrpcRequestRefusedByServer: FAILED_PRECONDITION/MemberDisabled(PAR::validator1::12203d9ed85f...)
      Request: subscription
