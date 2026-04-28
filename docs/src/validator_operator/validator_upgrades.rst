..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _validator_upgrades:

Upgrades
========

There are two types of upgrades:

Version upgrades (this corresponds to an upgrade from ``0.A.X`` to ``0.B.Y``)
and protocol upgrades (the actual version can remain the same, only the protocol is upgraded and it requires no action).

Version upgrades can be done by each node independently and only require
an upgrade of the docker-compose file or a ``helm upgrade`` for a
kubernetes deployment.
You must not delete or uninstall any Postgres database, change migration IDs or secrets for a version upgrade;
Make sure to read the :ref:`release_notes` to learn
about changes you may need to make as part of the upgrade.

Note that for docker-compose you must update the full bundle including
the docker compose file and the start.sh script and adjust
``IMAGE_TAG``. Only updating ``IMAGE_TAG`` is insufficient as the old
docker compose files might be incompatible with the new version.
