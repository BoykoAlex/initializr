#!/bin/bash
APP_DIR=`pwd`
BASE_DIR=${APP_DIR}/..

echo "========================================="
echo "Building initializr..."
cd ${BASE_DIR}/initializr
mvn clean install -Dmaven.test.skip

echo "========================================="
echo "Bundling spring CLI app into standalone jar"
cd ${APP_DIR}
spring jar start.jar app.groovy

echo "========================================="
echo "Pushing to CloudFoundry"
cf p

