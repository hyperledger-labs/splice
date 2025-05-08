..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _splice_app_apis:

Splice App APIs
===============

All Splice applications (e.g., the validator app) expose HTTP APIs. The
port they are exposed on is configured under ``admin-api.port``, e.g.,
this config file would expose the validator API on port 5003:

.. code-block::

   canton {
     validator-apps {
       validator {
         admin-api.port = 5003
         ...
       }
     }
   }

OpenAPI Specifications
----------------------

The APIs are documented using `OpenAPI specifications <https://www.openapis.org/>`_. You can download the OpenAPI specification for Splice's applications here: |openapi_download_link|.

API Stability
-------------

Endpoints in the files named ``APP-external`` are intended for external
consumption and are intended to stay backwards compatible across
releases. At this point, there might be some cases where backwards
compatibility is broken as we are still in early stages of
development. Any breaking changes in ``external`` APIs will be
documented in the release notes.

Endpoints in the files named ``APP-internal`` do not have any backwards compatibility guarantees.

Contract Payload Encoding
-------------------------

The ``Contract`` schema defined in ``common.yaml`` includes the
``payload`` of the given contract. This payload is encoded using the
same `schema used by the HTTP JSON API <https://docs.daml.com/json-api/lf-value-specification.html>`_.

.. _app-auth:

Authentication
--------------

Accessing Splice App APIs requires a JWT of the form:

.. code-block:: json

    {
      "sub": "ledgerApiUserId",
      "aud": "audience-of-app"
    }

The subject must be the ledger API user requests should be submitted
at, e.g., if you want to operate as the validator operator this is the
``ledger-api-user`` configured in your validator config.

The audience must be the audience specified in the ``auth`` section of your app.
The token must be signed with the specified algorithm and configuration.

E.g., for the config below the audience must be ``https://example.com`` and it must be
signed with an RS256 key that is found at the given `JWKS URL <https://datatracker.ietf.org/doc/html/rfc7517>`_.

.. code-block::

   canton {
     validator-apps {
       validator {
         auth {
           audience = "https://example.com"
           algorithm = "rs-256"
           jwks-url = "https://example.com/.well-known/jwks.json"
         }
         ...
       }
     }
   }

The token must be passed as an `OAuth2 Bearer token <https://datatracker.ietf.org/doc/html/rfc6750#section-2.1>`_, i.e., in an Authorization header of the form:

.. code-block::

   Authorization: Bearer yourtoken

Requests always operate on the primary party of the user specified as the subject in the token.
