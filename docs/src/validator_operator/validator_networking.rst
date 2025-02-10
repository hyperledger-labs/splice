..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _validator_network:

Validator Ingress & Egress requirements
=======================================

Ingress
-------

The validators have no external ingress requirements and don't need to whitelist any other SVs or validators.

Egress
------

The validators must be able to connect to all the SVs, thus whitelisting of egress on port 443 for the IPs of all the SVs is required (refer to :ref:`the network diagram <validator-network-diagram>` for a networking overview).
Note that egress is often allowed by default, so in many cases this requires no action.
