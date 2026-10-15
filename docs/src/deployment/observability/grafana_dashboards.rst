..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _metrics_grafana_dashboards:

Grafana Dashboards
++++++++++++++++++

The release bundle (|bundle_download_link|) contains two sets of Grafana dashboards
under the bundle root:

- ``validator-grafana-dashboards/``: for operators running a validator
  (validator app + participant). Includes common dashboards (JVM, K8s, participant) plus validator-specific dashboards.
- ``sv-grafana-dashboards/``: for operators running a Super Validator. Contains
  everything in the validator set plus SV-specific dashboards.

Import the folder matching your role into your Grafana instance.

.. note::

    The dashboards are built using queries specific for Prometheus native histograms.
