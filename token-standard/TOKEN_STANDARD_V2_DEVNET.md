# Token Standard V2 DevNet

Splice version: `0.6.0-snapshot.20260407.2423.0.v98974cc3`

Container image repository (compose `IMAGE_REPO`/ helm `imageRepo`): `ghcr.io/digital-asset/decentralized-canton-sync-dev`

This guide explains how to connect to the *Token Standard V2 DevNet*,
which is a temporary testing network aimed exclusively at organizations
involved in validating the V2 token standard. This network uses a single SV
node, which is operated by DA and allows connections from the same IPs as
the ones that connect to the normal DevNet. The network is reset and upgraded on a weekly
basis on Monday to consume changes to the token standard APIs.

If you have any questions or bug reports, please post them in the [Canton Network Forum](https://forum.canton.network/t/token-standard-v2-devnet-is-live/8502).

## Connecting to Token Standard V2 DevNet

You can connect to the Token Standard DevNet by spinning up a validator node
using the [guide for DevNet](https://hyperledger-labs.github.io/splice/validator_operator/validator_onboarding.html#onboarding-process-overview) with the following changes:
1. Any IPs that are allowed in regular DevNet are also allowed in Token-Standard-DevNet,
   so no extra steps required on your side to get yours allowed.
   If you anyway need to get a different IP allowed, request it in the [Canton Network Forum](https://forum.canton.network/t/token-standard-v2-devnet-is-live/8502).
2. Download the splice release from the repository and version at the top of this document.
    - If you're using the Docker Compose-Based Deployment
      make sure to change the environment variable `IMAGE_REPO` to the one at the top of this README.
    - If you're using the Kubernetes-Based Deployment
      make sure to set `imageRepo` to the one at the top of this README in your `values.yaml` file.
3. Use the following URLs:
   - SV URL: https://sv.sv-2.token-std-v2-dev.global.canton.network.digitalasset.com/
     - to get an onboarding secret: `curl -X POST https://sv.sv-2.token-std-v2-dev.global.canton.network.digitalasset.com/api/sv/v0/devnet/onboard/validator/prepare`
     - as the URL of your onboarding SV
   - Scan URL: https://scan.sv-2.token-std-v2-dev.global.canton.network.digitalasset.com/
4. Configure the protocol version to DEV in your participant by using the following environment variable (see [docs for ad-hoc configuration](https://hyperledger-labs.github.io/splice/deployment/configuration.html#adding-ad-hoc-configuration)):
   ```
   - name: ADDITIONAL_CONFIG_PV_DEV
     value: |
       canton.parameters.alpha-version-support=true
       canton.parameters.non-standard-config=true
       canton.participants.participant.parameters.alpha-version-support=true
       canton.participants.participant.parameters.initial-protocol-version=dev
     ```
