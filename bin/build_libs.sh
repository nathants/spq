#!/bin/bash
set -eux

# requires env vars like:
# export S3P_SNAPSHOTS_URL=s3p://$BUCKET/software/
# export S3_SNAPSHOTS_URL=s3://$BUCKET/software/snapshots

for repo in clj-schema clj-confs; do
    cd $(mktemp -d)
    tmp_path=$(pwd)
    git clone git@github.com:nathants/${repo}
    cd ${repo}
    name=$(echo ${repo} | cut -d- -f2)
    lein jar
    lein pom
    latest_hash=$(git log --format=%h|head -n1)
    aws s3 cp pom.xml $S3_SNAPSHOTS_URL/${name}/master-${latest_hash}/${name}-master-${latest_hash}.pom
    aws s3 cp target/*.jar $S3_SNAPSHOTS_URL/${name}/master-${latest_hash}/${name}-master-${latest_hash}.jar
    cd /tmp
    rm -rf ${tmp_path}
done
