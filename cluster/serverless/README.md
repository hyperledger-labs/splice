We implement functions and workflows for google serverless here.

# Deployment

- To deploy everything, invoke `run-job: update-serverless-workflows` in CircleCI.

# Testing and invoking Functions

- To test a function locally, run: `functions-framework --target main --debug` from the function's directory,
then curl `localhost:8080` with the required arguments.
- To invoke a deployed function, with auth, run:
`curl -H "Authorization: Bearer $(gcloud auth print-identity-token)" "https://<url>"`

