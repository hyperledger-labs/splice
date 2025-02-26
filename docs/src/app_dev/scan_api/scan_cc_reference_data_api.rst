..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _scan_cc_reference_data_api:

Scan Canton Coin Reference Data API
===================================

Alongside ephemeral status such as open rounds in the :ref:`scan_current_state_api`, there are more fixed data records for aspects of a Splice network.

DSO party ID
------------

The DSO party ID is unique to every Splice network, and is a signatory or observer of most core Splice Daml templates.
Therefore, you need to know it to correlate a Daml contract with a particular Splice network, or to construct a contract payload from scratch.

There's a simple way to retrieve the DSO party ID from Scan, with the `/v0/dso-party-id <scan_openapi.html#get--v0-dso-party-id>`_ endpoint.
Here's an example response:

.. code-block:: json

    {
      "dso_party_id": "DSO::122084177677350389dd0710d6516f700a33fe3488c5f2702dffef6d36e1dedcbfc17"
    }

.. note:: If you need the DSO party ID for an application, copy-pasting the above example will not work.
    The above has been deliberately altered into an invalid party ID.
