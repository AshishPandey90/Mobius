#!/bin/bash
curl -X POST -i "localhost:8080/mobius/workflow?workflowID=abcd-5678" -H "accept: application/json"
curl -X POST -i "localhost:8080/mobius/compute?workflowID=abcd-5678" -H "accept: application/json" -H "Content-Type: application/json"  -d @mosMaster.json
