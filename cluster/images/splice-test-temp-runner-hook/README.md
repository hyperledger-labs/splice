This image extends `ghcr.io/actions/actions-runner:latest` with a locally-built
version of the code, currently based on https://github.com/isegall-da/runner-container-hooks/tree/isegall/multi-service-containers.

We have upstreamed that change: https://github.com/actions/runner-container-hooks/pull/200, so if accepted, and a new release of actions-runner is made, we will remove this
and switch back to the upstream image.

Since we hope this is a temporary state, we did not invest in properly adding build
support for producing index.js. To update it:
- In the `runner-container-hooks` repo, run: `npm run build-all`
- Copy `packages/k8s/dist/index.js` from the `runner-container-hooks` repo to this directory
