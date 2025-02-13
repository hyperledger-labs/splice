..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _networks_and_usecases:


SV Operated networks and Use-cases
##################################

The Super Validators operate three(3) different networks:

1. DevNet
2. TestNet
3. MainNet


DevNet
******

This network functions as a staging ground for TestNet, enabling the configuration of DevNet to facilitate seamless exploration, including self-featured applications, CC tapping, and validator node self-service onboarding.
Super Validators manage DevNet with a commitment to high availability and operate on a best-effort basis, validating upgrades in a timely and appropriate manner.
They employ this environment for load testing and perform periodic resets approximately every three months to prevent hitting scalability bottlenecks that do not accurately represent those on MainNet.
In exceptional circumstances, if unresolved issues arise that are too costly to rectify without a reset, Super Validators may conduct an unscheduled reset.

DevNet offers app operators the opportunity to test onboarding workflows requiring fresh validator nodes.

.. note::
  All users are requested to engage with the network fairly, avoiding excessive load.
  The network is anticipated to remain open as long as its operational overhead remains manageable.

TestNet
*******

TestNet serves as a pre-production environment for Super Validators, Validators, and App Operators, allowing them to test upcoming software upgrades for SV and Validator nodes before deployment to MainNet.
This environment mirrors MainNet's configuration precisely.
App operators utilize TestNet to maintain long-running test instances of their applications, fulfilling two primary functions:

Facilitating the testing of upgrades while ensuring data continuity within their application code.
Allowing other app operators to test integrations with their applications.

.. note::
  App operators are expected to obtain the necessary TestNet-CC to cover traffic expenses from their liveness rewards, featured app rewards, and through collaboration with friendly Super Validators.


MainNet
*******

This network serves as the production environment for Super Validators, Validators, and App Operators, allowing them to deploy their applications to the network.



Testing Guidelines
##################

.. todo:: Add some more references for the testing guidelines

We recommend app operators to test their applications using the following testing approach:

1) **Unit Testing with Daml Script:** Begin by thoroughly testing your Daml code using Daml Script. This should encompass the full range of workflows within your application, including all dependencies, to validate the correctness of your logic and data models.

2) **Integration Testing in CI:** Implement integration tests within your Continuous Integration (CI) pipeline. These tests should utilize mocked dependencies and be executed against standalone Canton participants connected to a standalone Canton synchronizer (domain). This step ensures seamless interaction between components in a controlled environment.

3) **TestNet Deployment:** Deploy a test instance of your application on TestNet and integrate it with test instances of other applications that support the following critical use cases:

   a) Infrastructure upgrades
   b) App version upgrades
   c) Consuming app upgrades of dependencies


