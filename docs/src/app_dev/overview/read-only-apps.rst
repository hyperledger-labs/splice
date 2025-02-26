..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0



Read-Only Apps
==============

A Read-Only App is one that builds functionality by using read-only APIs.
Because they do not operate directly on the ledger, they cannot mutate or add data to it.
The data can be obtained from any application that has it publicly accessible.
An example of a Read-Only App would be a block explorer, i.e., an online tool that allows anyone to search
for historical information in a blockchain.

One application that provides publicly accessible visibility to the ledger is the Scan App of the Super Validators.
A Read-Only App can query the :ref:`app_dev_scan_api` to build new capabilities on top.

To read from the Scan API, you can use the :ref:`Scan Proxy endpoints <app_dev_validator_api>` provided by your own
Validator App. These should be the preferred approach to querying Scan, as it queries enough Scans as to
guarantee `Byzantine Fault Tolerance <https://en.wikipedia.org/wiki/Byzantine_fault>`_
while also only requiring you to call a single trusted endpoint.

If you do not host a Validator App or do not have a trustworthy validator, you'd have to implement BFT reads yourself.
To do that, read from at least ``f + 1`` scans where ``f = floor((total_num_scans - 1) / 3)``.
The consensus response (i.e., the response which is equal for ``f + 1`` scans) is the one that can be trusted.
You can get a list of all currently available Scans `in the GSF SV Info page <https://sync.global/sv-network/>`_
or use the :ref:`/v0/scans endpoint <app_dev_scan_api>` to get it programmatically.

As a last resort, querying a single Scan is a possibility.
However, this means you fully trust that single Scan instance to return correct responses,
so it should only be used if you have a good reason to trust it,
e.g., Super Validators can trust their own Scan instance.
