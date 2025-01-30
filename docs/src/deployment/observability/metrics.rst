..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _metrics:

Metrics
==========

Every node exposes a set of metrics on the port 10013 under the path `/metrics`. These metrics can be used to monitor the health of the node, and to diagnose issues.

.. note::
   Apps expose metrics on the port 10013, under the path `/metrics` in the Prometheus format.

For a validator the following components expose metrics:

- The validator app
- The participant

For a Super Validator, in addition, we have the following components:

- The SV app
- The scan app

Scraping the metrics
++++++++++++++++++++

The metrics are exposed in the Prometheus format, and can be scraped by a Prometheus server.
For a reference of the existing metrics please check the :ref:`metrics reference <metrics-reference>`.

Configuring a helm deployment to enable metrics
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

To enable metrics in a helm deployment, set the `metrics.enabled` value to `true` (default `false`) in the helm values. This will create a `ServiceMonitor` kubernetes custom resource. For this to work it would require that the Prometheus operator is installed in the cluster.

Alternatively you can add prometheus scrape annotations to the charts that are configured to scrape port 10013.

Configuring a docker compose deployment to enable metrics
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. note::
   Validator nodes only

When using docker compose for the deployment, the metrics are enabled by default. These can be accessed at `http://validator.localhost/metrics` for the validator app and at `http://participant.localhost/metrics` for the participant.
