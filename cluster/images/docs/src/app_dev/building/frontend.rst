Building and Testing App Frontends
==================================


OpenAPI/graphQL or gRPC-web
---------------------------

* Define a subset of the APIs exposed by your backend intended for frontend use using OpenAPI, graphQL or gRPC
* APIs exposed not for frontend are used for other apps to build upon or automation of the app
* Consider gRPC when you already use that for your backend. [connect-web](https://buf.build/blog/connect-web-protobuf-grpc-in-the-browser) or [grpc-web](https://github.com/grpc/grpc-web)
* Use OpenAPI or graphQL if you are already familiar with that and invested in that ecosystem.

Use of Codegens
---------------

* Use codegens to generate client-side types that match your Daml
  types where your backend exposes them directly

.. TODO(M1-90): If we go for grpc-web, consider how we want to handle integration with our TS codegen.

Use of the HTTP JSON API
------------------------

You can use the `HTTP JSON API <https://docs.daml.com/json-api/index.html>`_ under the following conditions:

* Small amount of data that can all be refetched & kept in memory
* Linear scan is acceptable for payload queries
* State-driven apps that only depend on the ACS, streaming endpoints
  as ACS updates rather than transaction streams
* Interaction with specific templates rather than generic frontends
  like Navigator

Testing App Frontents
---------------------

- Follow approach for backend testing to bring up dependencies
- Use backend integration test APIs to setup initialization to get to
  the state you want to test
- Focus on testing client side, consider intercepting write calls to
  server to speed up tests
- As for backend tests, rely on user/party ids to isolate tests from
  each other that need different states
