..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

Upgrades
========

There are two types of upgrades:

Version upgrades (this corresponds to an upgrade from ``0.A.X`` to ``0.B.Y``)
and protocol upgrades (the actual version can remain the same, only the protocol is upgraded).

Version upgrades can be done by each node independently and only require
a ``helm upgrade``. Make sure to read the :ref:`release_notes` to learn
about changes you may need to make as part of the upgrade.

Protocol upgrades are performed through :ref:`logical synchronizer upgrades <sv-logical-synchronizer-upgrades>`,
which allow upgrading the protocol version with very limited network downtime.

.. toctree::
   :hidden:

   sv_logical_synchronizer_upgrade
