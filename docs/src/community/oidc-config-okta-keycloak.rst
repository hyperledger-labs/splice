..
   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
..
   SPDX-License-Identifier: Apache-2.0

.. _okta_keycloak_oidc_config_guide:

Okta and Keycloak OIDC Configuration Guide
==========================================

.. warning::

   This section features solutions shared by community members. 
   While they haven’t been formally tested by the Splice maintainers, 
   users are encouraged to verify the information independently. 
   Contributions to enhance accuracy and completeness are always welcome.

To deploy a validator node, configure authentication using your preferred OIDC provider. 
This section provides instructions for Okta and Keycloak. 
For Auth0 instructions and more details on configuring authentication, see :ref:`Configuring Authentication <helm-validator-auth>`.

Thanks to Stéphane Loeuillet for contributing input in a `community discussion <https://github.com/global-synchronizer-foundation/docs/discussions/15#discussioncomment-12877002>`_, which forms the basis of the Okta section in this document.

Okta
----

Follow these steps to configure Okta as your validator’s OIDC provider:

1.  Create an application for the validator app backend.

    a. Navigate to “Admin Panel > Applications > Applications”, and click “Create App Integration”.
    b. Choose “API Services” as the sign-in method (not OIDC).
    c. For “App integration name”, enter ``Validator app backend``. 
    d. In General Settings, uncheck “Proof of possession”.
    e. Click “Save” and copy the “Client ID” and "Client Secret".

2.  Create an application for the wallet web UI.
  
    a. Navigate to “Admin Panel > Applications > Applications”, and click “Create App Integration”.
    b. Choose “OIDC” as the sign-in method then “Single-Page Application” as the application type.
    c. For “App integration name”, enter ``Wallet web UI``. 

       - In “Sign-in redirect URIs”, enter your wallet UI URL: ``https://wallet.validator.YOUR_HOSTNAME``
       - In “Sign-out redirect URIs”, enter your wallet UI URL: ``https://wallet.validator.YOUR_HOSTNAME``
       - In “Trusted Origins/Base URIs”, enter your wallet UI URL: ``https://wallet.validator.YOUR_HOSTNAME``
  
    d. Assign the app to the groups you want to have access to it.
    e. Click “Save” and copy the “Client ID”.

3.  Create an application for the CNS web UI.

    a. Navigate to “Admin Panel > Applications > Applications”, and click “Create App Integration”.
    b. Choose “OIDC” as the sign-in method then “Single-Page Application” as the application type.
    c. For “App integration name”, enter ``CNS web UI``. 

       - In “Sign-in redirect URIs”, enter your CNS UI URL : ``https://cns.validator.YOUR_HOSTNAME``
       - In “Sign-out redirect URIs”, enter your CNS UI URL : ``https://cns.validator.YOUR_HOSTNAME``
       - In “Trusted Origins/Base URIs”, enter your CNS UI URL : ``https://cns.validator.YOUR_HOSTNAME``
  
    d. Assign the app to the groups you want to have access to it.
    e. Click “Save” and copy the “Client ID”.

4.  Create a client for a custom audience (optional)

    a. If you want other applications to access the ledger API, create an additional client by following steps 1–3.

5.  Create a custom Authorization server per validator/environment

    a. Navigate to “Admin Panel > Directory > Groups”, click “Add Group” and name the group “canton_wallet_admin”.
    b. Add another group “canton_all.”
    c. Navigate to “Admin Panel > Security > API”
    d. In the “Authorization Servers” tab, click “Add Authorization Server”. Define a name and audience. Note that the authorization server is used for the M2M flow and must be different for each environment/validator for security reasons.
    e. In the “Scopes” tab, add a custom scope named ``daml_ledger_api``  and click “Set as a default scope”.
    f. In the “Claims” tab, change the “sub” value to “app.clientId”. If the default value is kept, it uses the user email, which requires formatting in values.yaml as ``‘ “ email.address@domain “ ‘``, without the spaces. This is necessary until the Helm chart is updated to properly quote them.
    g. In the “Access Policies” tab, create one policy for each of the three applications.

       - Name: ``Validator app backend``
       - Assigned to : ``Validator app backend`` created in step #1 
       - Click “Add rule.” Enter a “Rule Name” and check “Any user assigned the app” next to “User is” to define a rule that matches any user assigned to the app. Click “Create rule.”

       - Name: ``Wallet web UI``
       - Assigned to: ``Wallet web UI`` created in step #2
       - Click “Add rule.” Enter a “Rule Name” and check “Assigned the app and a member of one of the following” next to “User is.” Add the group “canton_wallet_admin” to define a rule that matches the group. Click “Create rule.”

       - Name: ``CNS web UI``
       - Assigned to: ``CNS web UI`` created in step #3
       - Click “Add rule.” Enter a “Rule Name” and check “Assigned the app and a member of one of the following” next to “User is.” Add the group “canton_all” to define a rule that matches the group. Click “Create rule.”

Keycloak
--------

Follow these steps to configure Keycloak as your validator’s OIDC provider:

1.  Create a realm

    a. Navigate to “Admin Panel > Manage realms”, click “Create realm”, and enter a realm name.

2.  Create a client for the Ledger API

    a. Navigate to “Admin Panel > Clients” and click “Create client”.
    b. Set the client type to “OpenID Connect”, enter ``ledger-api`` as the client ID, and click “Next”.
    c. In the “Capability config”, uncheck all fields and click “Next”.
    d. In the “Login settings”, leave the “Valid redirect URIs” and “Web origins” fields blank, then click “Save”.
    e. Navigate to the “Admin Panel > Client scopes” tab and click “Create client scope”.
    f. Enter ``daml_ledger_api`` as the name and select the type “Default”.
    g. In the “Mappers” tab, click "Configure a new mapper”. 
    h. Select “User Client Role” as the mapper type and name it ``daml_ledger_api_scope``. Enable “Add to Access Token” and disable all the other options, such as “Add to ID token”, “Add to userinfo”, and “Add to token introspection”. Click “Save”.
    i. To add this scope to the ``ledger-api`` client, navigate to “Admin Panel > Clients > ledger_api > Client scopes” and click “Add client scope”. Select ``daml_ledger_api`` and click “Add” with the type “Default”.
    j. Navigate to “Admin Panel > Realm Settings > Sessions”, and ensure that “Offline Session Max Limited” is enabled with a sufficient value for “Offline Session Max.” This enables refresh tokens, similar to “Allow Offline Access” in Auth0. Click “Save”.

3.  Create a client for a custom audience (optional)

    a. If your backend services require a different audience, create another client with the “Client ID” set to ``validator-app-api`` (e.g., ``https://validator.example.com/api``), and optionally define custom client scopes as described in step #2 to distinguish this API.

4.  Create a client for the validator app backend.

    a. Navigate to “Admin Panel > Clients” and click “Create client”.
    b. Set the client type to “OpenID Connect”, enter ``validator-app-backend`` as the client ID, and click “Next”.
    c. In the “Capability config”, only check “Client authentication” and “Service accounts”. Click “Next”.
    d. In the “Login settings”, leave the “Valid redirect URIs” and “Web origins” fields blank, then click “Save”.
    e. Copy the "Client ID" from the “Settings” tab and "Client Secret" from the “Credentials” tab. The Client ID in Keycloak is the name assigned to the client at creation (``validator-app-backend``).

5.  Create a client for the wallet web UI.

    a. Navigate to “Admin Panel > Clients” and click “Create client”.
    b. Set the client type to “OpenID Connect”, enter ``wallet-web-ui`` as the client ID, and click “Next”.
    c. In the “Capability config”, only check “Standard flow” and click “Next”.
    d. In the “Login settings”, set:
  
       - “Valid redirect URIs” to: ``https://wallet.validator.YOUR_HOSTNAME/*``
       - “Valid post logout redirect URIs” to: ``https://wallet.validator.YOUR_HOSTNAME``
       - “Web origins” to: ``https://wallet.validator.YOUR_HOSTNAME``
       - Click Save.

    e. Copy the "Client ID" from the “Settings” tab (``wallet-web-ui``).

6.  Create a client for the CNS web UI.

    a. Follow the same steps as for the wallet web UI, using ``cns-ui`` as the “Client ID”, setting: 

       - "Valid redirect URIs" to: ``https://cns.validator.YOUR_HOSTNAME/*``
       - “Valid post logout redirect URIs” to: ``https://cns.validator.YOUR_HOSTNAME``
       - “Web origins” to: ``https://cns.validator.YOUR_HOSTNAME``

    b. Copy the "Client ID" from the “Settings” tab (``cns-ui``).

