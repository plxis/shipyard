#!/bin/bash
set -e

instance_id=$(curl -s http://169.254.169.254/latest/meta-data/instance-id)

# Configure timezone
rm /etc/localtime && ln -s /usr/share/zoneinfo/GMT /etc/localtime

# Install dependent software packages; only jq and awslogs are mandatory for all hosts.
yum update -y
result=1
attempt=0
while [[ $attempt -lt 25 && $result -ne 0 ]]; do
  yum install -y jq awslogs nfs-utils
  result=$?
  [ $result -ne 0 ] && sleep 5
  attempt=$((attempt+1))
done

# Mount EFS targets
# TODO: What should the permissions be for the local mount?
echo "Mounting ${shipyard_efs_target} at ${shipyard_local_mount}"
mkdir -p "${shipyard_local_mount}"
mount -t nfs4 -o nfsvers=4.1,rsize=1048576,wsize=1048576,hard,timeo=600,retrans=2 ${shipyard_efs_target}: ${shipyard_local_mount}

mkdir -p "${users_local_mount}"
echo "Mounting ${users_efs_target} at ${users_local_mount}"
mount -t nfs4 -o nfsvers=4.1,rsize=1048576,wsize=1048576,hard,timeo=600,retrans=2 ${users_efs_target}: ${users_local_mount}

# Configure Docker
yum install -y docker
usermod -a -G docker ec2-user
service docker start
chkconfig docker on

# Configure shared users, cloudwatch logs, etc
if [[ -x /users/bootstrap/runOnNewHost.sh ]]; then
  /users/bootstrap/runOnNewHost.sh shipyard "${context}"
else
  echo "ERROR: User bootstrap script is not available. Expected to be mounted at /users/bootstrap/runOnNewHost.sh"
fi

# Add shipyard group if not present in /users/etc/group (use group id 2609, matching value of "project.group.id" in build.xml)
if grep -q "shipyard" /etc/group; then
  groupmod -g 2609 shipyard
else
  groupadd -g 2609 shipyard
fi
# Ensure log directory exists and shipyard group can write to it (utilized by docker container)
mkdir -p /var/log/shipyard
chgrp shipyard /var/log/shipyard/
chmod g+w /var/log/shipyard/

# Configure cloudwatch logs for shipyard
# Note: The directory containing the monitored log file (/var/log/shipyard) should be mounted at /opt/shipyard/log
#       when executing the shipyard docker container
cat > /etc/awslogs/config/shipyard.conf <<EOFLOG
[shipyard]
log_group_name=${log_group}
log_stream_name=shipyard-$instance_id
datetime_format=%b %d %H:%M:%S
file=/var/log/shipyard/shipyard.log
EOFLOG
service awslogs restart

# Create an alias for gathering secrets used by shipyard (AWS keys, vault secrets)
if [[ ! -f /etc/profile.d/shipyard-alias.sh ]]; then
  mkdir -p /etc/profile.d
  cat > /etc/profile.d/shipyard-alias.sh <<EOF
alias shipyard-keys='if [ -z  ]; then read -s -p '\''Enter AWS_ACCESS_KEY_ID: '\'' AWS_ACCESS_KEY_ID && export AWS_ACCESS_KEY_ID && echo '\'''\''; fi && if [ -z  ]; then read -s -p '\''Enter AWS_SECRET_ACCESS_KEY: '\'' AWS_SECRET_ACCESS_KEY && export AWS_SECRET_ACCESS_KEY && echo '\'''\''; fi && if [ -z  ]; then read -s -p '\''Enter vault unseal key: '\'' VAULT_UNSEAL_KEY && export VAULT_UNSEAL_KEY && echo '\'''\''; fi && if [ -z  ]; then read -s -p '\''Enter vault authentication token: '\'' VAULT_AUTH_TOKEN && export VAULT_AUTH_TOKEN  && echo '\'''\''; fi'
EOF
fi
chmod +x /etc/profile.d/shipyard-alias.sh

# Create a script for executing the shipyard docker container
cat > /usr/local/bin/shipyard-docker <<EOFSCR
#!/usr/bin/env bash
if [[ -z \$${AWS_ACCESS_KEY_ID} ]]; then
    read -s -p "Enter AWS_ACCESS_KEY_ID: " AWS_ACCESS_KEY_ID
    echo ""
fi
if [[ -z \$${AWS_SECRET_ACCESS_KEY} ]]; then
    read -s -p "Enter AWS_SECRET_ACCESS_KEY: " AWS_SECRET_ACCESS_KEY
    echo ""
fi
if [[ -z \$${VAULT_UNSEAL_KEY} ]]; then
    read -s -p "Enter VAULT_UNSEAL_KEY: " VAULT_UNSEAL_KEY
    echo ""
fi
if [[ -z \$${VAULT_AUTH_TOKEN} ]]; then
    read -s -p "Enter VAULT_AUTH_TOKEN: " VAULT_AUTH_TOKEN
    echo ""
fi

# Execute docker container
docker run --rm -it --cap-add=IPC_LOCK -e VAULT_UNSEAL_KEY -e VAULT_AUTH_TOKEN -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -v /shipyard-data/vault:/opt/vault/store -v /var/log/shipyard:/opt/shipyard/log ${docker_image} "\$@"
EOFSCR
chmod a+x /usr/local/bin/shipyard-docker