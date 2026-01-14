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

.. todo:: link to network or component diagram to improve understanding

Scraping the metrics
++++++++++++++++++++

We use OpenTelemetry to build the metrics and they are exposed in the Prometheus format, and can be scraped by a Prometheus server.
For a reference of the existing metrics please check the :ref:`metrics reference <metrics-reference>`.


Histograms
----------

We expose the histograms as `exponential histograms <https://opentelemetry.io/docs/specs/otel/metrics/data-model/#exponentialhistogram>`_ which are converted to `prometheus native histograms  <https://prometheus.io/docs/specs/native_histograms/>`_.

.. note::

    Prometheus support must be enabled using the flag `-enable-feature=native-histograms`.

    Native histograms are available only on the protobuf format so Prometheus will switch to the protobuf collection format.


You can switch back to regular histograms node by adding the following environment variable to a node: `ADDITIONAL_CONFIG_DISABLE_NATIVE_HISTOGRAMS="canton.monitoring.metrics.histograms=[]"`

Enabling metrics
----------------

Configuring a helm deployment to enable metrics
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

To enable metrics in a helm deployment, set the `metrics.enable` value to `true` (default `false`) in the helm values. This will create a `ServiceMonitor` kubernetes custom resource. For this to work it would require that the Prometheus operator is installed in the cluster.

Alternatively you can add prometheus scrape annotations to the charts that are configured to scrape port 10013.

Configuring a docker compose deployment to enable metrics
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

.. note::
   Validator nodes only

When using docker compose for the deployment, the metrics are enabled by default. These can be accessed at `http://validator.localhost/metrics` for the validator app and at `http://participant.localhost/metrics` for the participant.

.. _enable_extra_metric_triggers:

Enabling extra metric triggers
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The validator app can be configured to run a trigger
that polls the topology state and exports metrics summarizing that state.
These metrics have the prefix ``splice.synchronizer-topology``.
See the :ref:`validator-metrics-reference` for the concrete set of metrics.

This trigger is disabled by default.
As per the information in :ref:`Adding ad-hoc configuration <configuration_ad_hoc>`,
add an environment variable
``ADDITIONAL_CONFIG_TOPOLOGY_METRICS_EXPORT=canton.validator-apps.validator_backend.automation.topology-metrics-polling-interval = 5m``
to enable the trigger with a polling interval of 5 minutes.


.. _metrics_grafana_dashboards:

Grafana Dashboards
++++++++++++++++++

The release bundle (|bundle_download_link|) contains a set of Grafana dashboards that are built based on the metrics above.
These dashboards can be imported into a Grafana instance. The dashboards are built assuming a K8s deployment, and may need to be modified for other deployment types.
The dashboards can be found under the `grafana-dashboards` folder in the release bundle.

.. note::

    The dashboards are built using queries specific for Prometheus native histograms.
