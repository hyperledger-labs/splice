..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _validator_hardware_requirements:

Validator Hardware Requirements
===============================

This section describes hardware requirements for running a
validator. Note that these are reference values. Actual requirements
can vary based on usage of your validator. We recommend
monitoring your production validator nodes with respect to
CPU and memory usage of all components and disk usage of the
database, and adjust the resourcing as needed.

The requirements include both the validator and participant container.

These requirements are largely identical between the docker-compose
based deployment and the k8s deployment but exclude overhead from k8s
itself or ingress.

.. list-table::
   :header-rows: 1

   * - Usage
     - CPUs
     - Memory
     - DB CPUs
     - DB Memory
     - DB size
   * - Experiments on local laptop or minimal VM
     - 1
     - 6GB
     - 1
     - 1GB
     - 1GB
   * - Production validator with little activity
     - 2
     - 8GB
     - 2
     - 4GB
     - 10GB
   * - Production validator for an app provider with moderate activity
     - 2
     - 16GB
     - 2
     - 4GB
     - 100GB

Database Latency
----------------

Components are relatively sensitive to database latency. If you use a
managed database offering like GCP CloudSQL, it is recommended that you allocate
it in the same region and zone that your cluster runs in.
