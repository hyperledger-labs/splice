The original openapi spec obtained from `$PARTICIPANT_JSON_API/docs/openapi`
is not fully compatible with our version of `openapi-generator-cli`.
Some manual changes have been made to make sure it works:

- Changed version from `3.1.0` to `3.0.0`
- Changed the definition of `Tuple2_String_String` to be compatbile with `3.0.0` from:
```yaml
    Tuple2_String_String:
      title: Tuple2_String_String
      type: array
      prefixItems:
      - type: string
      - type: string
```
to:
```yaml
    Tuple2_String_String:
      title: Tuple2_String_String
      type: array
      items:
        type: string
      minItems: 2
      maxItems: 2
```
which should be equivalent.
- Changed the definition of several `oneOf` instances. For example:
```yaml
    SIGNING_ALGORITHM_SPEC_ED25519:
      title: SIGNING_ALGORITHM_SPEC_ED25519
      type: object
```
is now:
```yaml
    SIGNING_ALGORITHM_SPEC_ED25519:
      title: SIGNING_ALGORITHM_SPEC_ED25519
      type: object
      properties:
        SIGNING_ALGORITHM_SPEC_ED25519:
          type: object
```
This is equivalent in the context of a `oneOf` without discriminator.
Without this change, the Typescript generator would only define `unrecognizedValue`
and cause compilation to fail when the intended type was provided.
- `recordTime` fields are changed to `type: string` instead of `$ref: '#/components/schemas/Timestamp'`. Indeed, the API response contains strings.