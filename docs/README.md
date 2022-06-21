# Docs

Documentation for all CC functionality. 
Intended to be published to https://network.canton.global.

## Live-preview

Running `./live-preview.sh` builds the docs using
`sphinx-autobuild`. Changes to your docs will be picked up
automatically without having to run a full build.

## Full build

`./preview.sh` runs the full build and then launches a webserver
serving the results. This is mainly useful if you’re modifying themes
or other changes that are not picked up by `sphinx-autobuild`.

## Minikube deployment 

The docs can be deployed to a local `minikube` kubernetes cluster. To do so:

1. Build the docs: `make html`
2. Start minikube: `minikube start`
3. Set docker-env: `eval $(minikube docker-env)`
4. Build a local docker image: `docker build -t docs:latest .`
5. Load the image into minikube: `minikube image load docs`
6. Create deployment: `kubectl apply -f minikube-deployment.yaml`
7. Forward the port: `kubectl port-forward service/docs-service 7080:80` 

The docs page should now be accessible in a browser at `localhost:7080`

### Changes 

When making a change, these are the steps to rebuild a new image and deploy it:

1. Rebuild the docs: `make html`
2. Rebuild the image: `docker build -t docs:latest .`
3. Delete the deployment: `kubectl delete deployment docs-deployment`
4. Remove the image from minikube's cache: `minikube image rm docs`
5. Load the image into minikube: `minikube image load docs`
6. Rerun the deployment: `kubectl apply -f minikube-deployment.yaml`