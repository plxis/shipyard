#!/bin/bash

cat << EOF > /etc/sudoers.d/shipyard
# Allow shipyard user to use yum
shipyard   ALL=(ALL)  NOPASSWD: /bin/yum
Defaults:shipyard     !requiretty
EOF
