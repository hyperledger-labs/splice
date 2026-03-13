..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _sv-logical-synchronizer-upgrades:

Logical Synchronizer Upgrades
=============================

.. warning:: Logical Synchronizer Upgrades (LSU) are still in development and the instructions here are intended as a preview primarily targeted at Super Validators but will likely change in minor ways before the full release.


Logical synchronizer upgrades (LSUs) allow upgrading the protocol version of a synchronizer
**with very limited network downtime** and no operational overhead for validator operators and app devs around upgrades.
Super validators still have to perform operational steps to deploy successor nodes and schedule the upgrade but those are done asynchronously before the actual upgrade happens.

.. _sv-lsu-overview:

High-Level Overview
-------------------

1. A new Canton release with an updated protocol version becomes available, along with a compatible Splice release. For testing purposes or in some disaster recovery scenarios this can also be the same version and/or protocol version.
   This release supports both the old and new protocol version.
2. Validators and super validators upgrade to the new release, but continue running the original physical synchronizer with the old protocol version. This is a regular upgrade and can be done asynchronously
   but must be done before the actual upgrade time.
3. A vote is created in the SV UI to schedule an LSU through the ``nextScheduledLogicalSynchronizerUpgrade`` field in ``DsoRulesConfig``.
   The schedule includes

   1. **topology freeze time**: after this time, no topology transactions can be sequenced until the upgrade time, so in particular no parties can be added and no Daml packages can be vetted
   2. **upgrade time**: at this time Daml transactions on the original physical synchronizer will time out and new Daml transactions will run on the new physical synchronizer
   3. **new physical synchronizer serial**: usually just the old serial incremented by 1
   4. **new protocol version**: the protocol version of the successor synchronizer

4. All SVs deploy *successor* synchronizer nodes (sequencer, mediator, and optionally CometBFT if DABFT is not used) alongside their existing nodes. Note: There is no new participant, the participant is tied to a logical synchronizer so it does not change on an LSU. As part of that they also :ref:`configure <lsu_deployment_changes>` the successor synchronizer in their SV and scan config.
   This deployment should be completed before the freeze time.
5. At the scheduled **topology freeze time**, the SV app automation of each SV transfers the topology state to the successor nodes and publishes the sequencer URL for the new sequencer in the topology state (this is the only topology transaction that can be published after the freeze time).
6. Between the topology freeze time and the upgrade time, SV app automation will periodically send special health check events on the new physical synchronizer to verify its health.
   Each super validator should use their metrics to validate that they observe at least one event from each other super validator as well as that the BFT peer connections (CometBFT or DABFT) of the successor nodes are healthy.

   .. todo:: add more details once we have added this

7. At the scheduled **upgrade time**, participants automatically connect to the successor synchronizer.
   The SV automation transfers traffic control state from the current sequencer to the successor.
   The successor physical synchronizer may be configured with a lower initial rate limit that will be
   raised by the SV app after a configurable amount of time to avoid an initial traffic surge on the new synchronizer.

   .. todo:: add more details once we have added this.

9. The successor physical synchronizer is now fully usable. Super Validators update their :ref:`configuration <lsu_deployment_changes>` to mark the original synchronizer as legacy
   and successor as the current synchronizer.
10. After 30 days, the super validators remove the old physical synchronizer node deployment. Super Validators update their :ref:`configuration <lsu_deployment_changes>` to remove the
    legacy synchronizer configuration.

LSU Cancellation
~~~~~~~~~~~~~~~~

Between the topology freeze time and and upgrade time, the upgrade can be cancelled if the successor physical synchronizer is deemed unhealthy, e.g., because the health checks fail.
To do so, a threshold of super validators must send a ``POST`` request to the ``/v0/admin/synchronizer/lsu/cancel`` endpoint on the SV API.

Disaster Recovery through Roll-Forward LSU
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

In case of a disaster that causes the current physical synchronizer to become unavailable, an LSU can be used as a roll-forward recovery mechanism.
The procedure is similar to a regular LSU but because the current physical synchronizer is unusable, the coordination through a vote and topology transactions is not possible and instead validators and super validators need to manually initiate the upgrade.

Concretely, the procedure is as follows:

1. The old physical synchronizer is deemed broken and the last sequenced message was at record time R.
2. Super validators deploy successor nodes. Depending on the issue, the successor nodes may be configured with older image and protocol versions if the issue is limited to the new version.
3. Super validators configure their SV app app to transfer the topology and traffic state from the old physical synchronizer to the successor nodes.

   .. todo:: add more details once this is implemented

4. Super Validators configure their scan to indicate to validators that they should roll forward to a new synchronizer.

5. Validator app automation picks up that configuration and initiates a manual roll-forward LSU to the new synchronizer.

Note that depending on how exactly the old synchronizer failed, validators may desynchronize if some validators have observed a transaction before the failure while others have not.
In that case, the participant will produce ACS mismatches that should be resolved using the `standard ACS mismatch resolution process <https://docs.digitalasset.com/operate/3.4/howtos/troubleshoot/commitments.html>`_ after migrating to the new physical synchronizer.

This procedure can also be used for recovering from a failed LSU. There are two relevant cases:

1. The LSU did not get cancelled before the upgrade time but no Daml transactions and topology transactions were able to be sequenced on the successor physical synchronizer after the upgrade time. In this case, the original successor synchronizer can be thrown away and replaced
   by a new successor synchronizer with a serial incremented by 1 (so 2 compared to the original non-successor synchronizer).
2. The LSU proceeded and some transactions did get sequenced on the successor physical synchronizer but the successor physical synchronizer then became unusable. The procedure is the same in this case but
   the SVs should keep both the original synchronizer and the broken successor synchronizer running (assuming it can still serve events just not sequence new messages) to allow nodes to catchup first and spin up a new successor synchronizer on the side
   so they are running 3 synchronizer nodes for some period of time. Allowing nodes to catch up as much as possible limits the potential for desynchronization requiring manual resolution through ACS commitment mismatches.


.. _lsu_deployment_changes:

Super Validator Deployment Changes
----------------------------------

.. todo:: update helm values and link them here

LSU requires deployment changes for super validators. Concretely:

1. Participants are now preserved as part of LSUs. So if you previously assumed participant, sequencer and mediator always come as one unit per migration id, you now need to move the participant out of that.
2. The ``domain`` value on the sv app helm chart should be replaced by ``synchronizers``. ``synchronizers.current`` replaces the synchronizer previously configured through ``domain``. ``synchronizers.successor``
   should be configured to the successor physical synchronizer when that is deployed. After the upgrade, ``synchronizers.current`` becomes ``synchronizers.legacy`` and ``synchronizers.successor`` becomes ``synchronizers.current``. The legacy configuration should be removed together with removing the old physical synchronizer after 30 days.
   The CometBFT configuration also moves under ``synchronizers.(current|successor|legacy)``.
3. The ``sequencerAddress`` and ``mediatorAddress``values in scan should be replaced by ``synchronizers.current.sequencer`` and ``synchronizers.current.mediator``. The corresponding values under ``synchronizers.successor`` should be set together with
   the deployment of the successor physical synchronizer. After the upgrade ``successor`` becomes ``current`` and ``current`` is removed.
4. When using DABFT as the successor node, further changes will be required. Most notably the cometbft node goes away as DABFT runs as part of the sequencer pod. The sequencer pod and SV app will require some additional configuration. Details of this will be added later.
