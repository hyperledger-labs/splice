Building and Testing App Frontends
==================================

* How to use codegen and OpenAPI or grpc-web to transport data?
* When and how to use JSON API?
* How to test app frontend?
* idea for fast test execution: use backend integration test to bring system into a state where frontend testing can commence then test proper rendering and client-side-only state transitions, but intercept write-calls to server to allow parallel test execution
* use tests where client-side write calls happen sparingly
