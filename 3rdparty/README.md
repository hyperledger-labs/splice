# Sources vendored in from 3rd parties

List of vendored libraries/files and reason why they were vendored:
- `protobuf/google/rpc/status.proto`:
   referenced by the Daml Ledger API files and vendored in the `daml` repo at
   https://github.com/digital-asset/daml/blob/main/3rdparty/protobuf/google/rpc/status.proto.
   We were required to vendor that as otherwise the `buf` tool would complain during linting.

