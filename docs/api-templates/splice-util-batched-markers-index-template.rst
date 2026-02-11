..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _package-batched-markers:

splice-util-batched-markers docs
================================

This package provides the ``BatchedMarkersProxy`` contract which can
be used to create a batch of ``FeaturedAppActivityMarkers`` in a
transaction with a single view which is more efficient to process. To
use it first create one long-lived ``BatchedMarkersProxy`` contract
and then use ``BatchedMarkersProxy_CreateMarkers`` to create the
actual markers. Note that for this to work all beneficiaries and the
provider must have vetted the DAR.

.. toctree::
   :maxdepth: 3
   :titlesonly:

{{{body}}}
