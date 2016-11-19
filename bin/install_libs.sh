#!/bin/bash
set -eux

for repo in clj-schema clj-confs; do
    cd $(mktemp -d)
    tmp_path=$(pwd)
    git clone https://github.com/nathants/${repo}
    cd ${repo}
    latest_hash=$(git log --format=%h|head -n1)
    sed -r -i "s:defproject ([^ ]+) .*:defproject snapshots/\1 \"master-${latest_hash}\":" project.clj
    lein install
    cd /tmp
    rm -rf ${tmp_path}
done
