..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _sv-monitoring:

Monitoring
==========

Metrics
+++++++

Every SV deployment exposes a set of metrics on the port 10013 under the path `/metrics`. These metrics can be used to monitor the health of the validator node, and to diagnose issues. The metrics are exposed in the Prometheus format, and can be scraped by a Prometheus server.

The following components expose metrics:

- The sv app
- The scan app
- The validator app
- The participant

Configuring a helm deployment to enable metrics
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

To enable metrics in a helm deployment, set the `metrics.enabled` value to `true` (default `false`) in the helm values. This will create a `ServiceMonitor` kubernetes custom resource. For this to work it would require that the Prometheus operator is installed in the cluster.

Alternatively you can add prometheus scrape annotations to the charts that are configured to scrape port 10013.
