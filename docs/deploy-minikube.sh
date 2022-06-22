#!/bin/sh

kubectl delete deployment/docs-deployment service/docs-service
kubectl apply -f minikube-deployment.yaml

# Max timeout in seconds
TIMEOUT=10
READY=false

start=$(date +%s)
while [ $(expr $(date +%s) - $start) -lt $TIMEOUT ]
do
    running=$(kubectl get pods --field-selector=status.phase=Running | grep Running)

    if [ "$running" == "" ]; then
        echo "Waiting for pod to enter Running phase..."
    else
        READY=true
        break
    fi
    sleep 0.5
done

if [ "$READY" == "true" ]; then
    echo "Pod is running! Forwarding port..."
    kubectl port-forward service/docs-service 7080:80
else
    echo "Operation timed out! Pod did not become ready within $TIMEOUT seconds."
    exit 1
fi
