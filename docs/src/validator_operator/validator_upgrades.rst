..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

Upgrades
========

There are two types of upgrades:

Upgrades to a new minor version (given that we have not yet reached
``1.0``, this corresponds to an upgrade from ``0.A.X`` to ``0.A.Y``)
and major upgrades (this corresponds to an upgrade from ``0.B.X`` to
``0.C.Y``).

Minor upgrades can be done by each node independently and only require
an upgrade of the docker-compose file or a ``helm upgrade`` for a
kubernetes deployment. Make sure to read the :ref:`release_notes` to learn
about changes you may need to make as part of the upgrade.

Major upgrades currently require a more complex :ref:`procedure <validator-upgrades>`
with network-wide coordination and downtime.

.. toctree::
   :hidden:

   validator_major_upgrades
