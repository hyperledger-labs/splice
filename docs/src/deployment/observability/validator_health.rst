..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _validator_health:

Validator Health
================

You can check your validator's health using the readiness endpoints. All CN applications provide the ``/readyz`` and ``/livez``
endpoints, which are used for readiness and liveness probes.

* **Checking readiness**

  * In Kubernetes: readiness and liveness probes are already configured.

    You can also manually check validator readiness with the following command:

    .. code-block:: yaml

        kubectl exec <pod-name> -n <namespace> -- curl -v https://localhost:5003/api/validator/readyz

  * In Docker: run for example this command to check validator liveness inside a container:

    .. code-block:: yaml

        docker exec <container-name> -- curl -v https://localhost:5003/api/validator/livez

  You should expect in both case HTTP status code 200 if the validator is ready and live.

* **Using metrics**

  The ``splice_store_last_ingested_record_time_ms`` metric represents the last ingested record time in each validator store. It can be used to track general activity of the node:

  * If this value continue to increase over time, your node is active and stays in sync with the network.
    Note that it only advances if your node actually ingests new transactions.
    For a validator collecting validator liveness rewards this happens every round so you should expect your lag to never go above 20min.

  * If it remains static, further investigation may be required.

  For more details and to visualize this metric on its dedicated dashboard `Splice Store Last Ingested Record Time`, refer to the documentation about :ref:`Metrics <metrics>`.
