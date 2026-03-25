# Token Standard V2

TODO: move this file to the corresponding token-standard-v2 directory once it exists.

## Token Standard V2 DevNet

The Token Standard V2 is under development.
You can read the grant proposal [here](https://github.com/canton-foundation/canton-dev-fund/pull/97).

We have deployed a Token Standard V2 DevNet network for any validators that would start testing it.

- Scan URL: https://scan.sv-2.token-std-v2-dev.global.canton.network.digitalasset.com/
- SV URL: https://sv.sv-2.token-std-v2-dev.global.canton.network.digitalasset.com/ (necessary as sponsoring SV)

This acts just like DevNet. This means:
- Any IPs that are allowed in regular DevNet are also allowed in Token-Standard-DevNet,
  so no extra steps required on your side to get yours allowed.
- You can generally follow the [regular onboarding instructions](https://hyperledger-labs.github.io/splice/validator_operator/validator_onboarding.html#onboarding-process-overview).
  - You will need to configure your participant to use the development protocol version.
    The easiest way is to [add the following environment variable](https://hyperledger-labs.github.io/splice/deployment/configuration.html#adding-ad-hoc-configuration) into your participant:
    ```
    - name: ADDITIONAL_CONFIG_PV_DEV
      value: |
        canton.parameters.alpha-version-support=true
        canton.parameters.non-standard-config=true
        canton.participants.participant.parameters.alpha-version-support=true
        canton.participants.participant.parameters.initial-protocol-version=dev
      ```
    
- You can generate your own onboarding secret by calling: `curl -X POST https://sv.sv-2.token-std-v2-dev.global.canton.network.digitalasset.com/api/sv/v0/devnet/onboard/validator/prepare`

Do keep in mind:
- The cluster will be reset and upgraded roughly every week on Monday/Tuesday.
- There's both experimental Daml templates and experimental Canton code running,
  meaning that things might not work as expected. We'd be happy to receive any bug reports from you!
