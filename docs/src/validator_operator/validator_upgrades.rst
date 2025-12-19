..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _validator_upgrades:

Upgrades
========

There are two types of upgrades:

Upgrades to a new minor version (given that we have not yet reached
``1.0``, this corresponds to an upgrade from ``0.A.X`` to ``0.A.Y``)
and major upgrades (this corresponds to an upgrade from ``0.B.X`` to
``0.C.Y``).

Minor upgrades can be done by each node independently and only require
an upgrade of the docker-compose file or a ``helm upgrade`` for a
kubernetes deployment.
You must not delete or uninstall any Postgres database, set migration IDs or secrets, or set the ``migrating`` flag for a minor upgrade; these steps are meant for major upgrades and will only cause problems here.
Make sure to read the :ref:`release_notes` to learn
about changes you may need to make as part of the upgrade.

Note that for docker-compose you must update the full bundle including
the docker compose file and the start.sh script and adjust
``IMAGE_TAG``. Only updating ``IMAGE_TAG`` is insufficient as the old
docker compose files might be incompatible with the new version.

Major upgrades currently require a more complex :ref:`procedure <validator-upgrades>`
with network-wide coordination and downtime.

.. toctree::
   :hidden:

   validator_major_upgrades
