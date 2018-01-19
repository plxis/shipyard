# shipyard


[![Build Status](https://travis-ci.org/plxis/shipyard.svg?branch=master)](https://travis-ci.org/plxis/shipyard)

Shipyard is a deployment management platform that operates on environment-specific configurations to create/manage running environments.

## General Concepts

Shipyard's primary abstraction is an _environment_.

### Environment

An environment definition is composed of:

* Name
* Description
* Environment Version
* Modules
  * Module Version
  * Inventory
  * Configuration Values

NOTE: Because the environment definition is securely stored within the Shipyard backend, it can contain both sensitive and non-sensitive configuration values.

A sample environment file can be found in `resources/environment.sample.json`

### Modules

Modules encapsulate the deployment package and configuration values for a given product and are made available to a Shipyard deployment via a yum deployment package provided by the product module.

### Storage Backend

Shipyard supports configurable storage backends for secure storage of environment configurations. The default implementation uses [Vault](https://projectvault.io).

### Superintendent

Shipyard provides the `superintendent` tool to manage environments. The following commands are available:

```none
  delete     Deletes a storage path.
  deploy     Initiates an environment deployment.
  keys       Lists all keys stored in a given storage path.
  read       Reads a key or all keys from a given storage path.
  write      Writes a value to a key at a given storage path.
```

### Superedit

Shipyard provides the `superedit` tool to interactively edit environment configurations by retrieving the configuration from storage and providing an interactive buffer.

```bash
$ superedit --help
```

## Setting Up a Standalone Shipyard Server

### Create Configuration File

Copy the `conf/shipyard-config.json.sample` file as `shipyard-config.json`, and modify accordingly.

### Run Playbook

Execute the shipyard playbook against the target host. The user specified should have password-less sudo privileges:

```bash
$ ansible-playbook -i <target_host>, -e @shipyard-config.json -u <user> -k ansible/site.yml
```

### Configure Vault

SSH into the host and configure the vault server as follows:

#### Start Vault Service

```bash
$ sudo su - shipyard -c "/opt/vault/bin/vault-ctl.sh start"
```

#### Initialize Vault

Initializing the vault requires (2) settings:

* *key-shares*: Number of fragments from which to construct the master key
* *key-threshold*: Number of key shares required to unseal the vault

The recommended values for a production setup are represented below:

```bash
$ sudo su - shipyard -c "vault init -key-shares=5 -key-threshold=3"
```

Initializing the vault will generate (2) critical pieces of information:

* *Unseal Keys* - Keys required to unseal the vault
* *Initial Root Token* - Root token required to authenticate to the vault to perform subsequent 
  operations

Example:

```none
Unseal Key 1: 5szYqEUZWuvQFsToB1Thn57pWRWealZf65z1xtLm3og=
Unseal Key 2: RszYqEUZWuv23423525EIjafsi34alZf65z1xtLm3hi=
Unseal Key 3: x4iZWuvEIjafsi34aTYqEUZWuvWRWealZf65z1xRtLm=
Initial Root Token: bd7ccdc3-f748-eb30-68c4-9a22cb168b8c
```

**IMPORTANT** - This information must be stored securely in order to perform maintenance on the vault.

#### Unseal Vault

To unseal the vault, repeat the following command with the number of required shared keys specified during the initializtion.

The unseal process for the recommended production setup is represented below:

```bash
$ sudo su - shipyard -c "vault unseal"
$ sudo su - shipyard -c "vault unseal"
$ sudo su - shipyard -c "vault unseal"
```

When prompted, enter the values for the unseal keys generated during the init step.

#### Authenticate to Vault

The remaining configuration steps require authentication with the initial root token.

```bash
$ sudo su - shipyard -c "vault auth"
```

When prompted, enter the value for the root token generated during the init step.

#### Enable audit backend

To enable basic auditing (via log file), run the following:

```bash
$ sudo su - shipyard -c "vault audit-enable file -path=vault-audit file_path=/opt/vault/log/audit.log"
```

NOTE: The path to the audit log file should be the same as the path configured in `shipyard-config` for the 'vault-log' Splunk-forwarder monitor.

#### Create Superintendent Service Token

The *superintendent* tool will use a long-lived service token to perform vault operations. This token should be rotated regularly.

```bash
$ sudo su - shipyard -c "vault token-create -display-name=superintendent-service"
```

Example:

```none
Key             Value
---             -----
token           74b824eb-0c31-971c-3daf-f2eab8e04614
token_accessor  b3553740-0b31-065f-ccea-6888fac3f398
token_duration  0s
token_renewable false
token_policies  [root]
```

#### Configure Superintendent

As the *shipyard* user, create (or modify) `$SHIPYARD_HOME/conf/superintendent-secure.properties`:

```bash
$ sudo su - shipyard -c "vi ${SHIPYARD_HOME}/conf/superintendent-secure.properties"
```

Add the generated token to the file:

```none
vaultToken=74b824eb-0c31-971c-3daf-f2eab8e04614
```

To enable automated remote backups of the vault backend, add the following properties:

```none
vaultArchiveRemoteUser=<remote_user>
vaultArchiveRemoteHost=<remote_host>
vaultArchiveRemotePort=<remote_port>
vaultArchiveRemoteDir=<remote_dir>
```

NOTE: Ensure the following:

* Remote host is configured to accept authenticated SSH via certificates
* Shipyard public key is present in the remote user's `~/.ssh/authorized_keys` file in the remote host.

#### Configure remote backup server (optional)

Create shipyard user and set a password

```bash
$ useradd shipyard -m
$ passwd shipyard
```

From shipyard server, send ssh key to backup server

```bash
$ ssh-copy-id shipyard@remote-backup-host
```

On the remote backup host, ensure the correct permissions on the remote user's SSH 
configuration:
```
chmod 700 ~/.ssh/
chmod 600 ~/.ssh/authorized_keys
```


## Usage
The `superintendent` tool is used to manage environments.

```bash
$ superintendent -help
```

## Restoring the Vault Backend from Backup
The vault storage backend is backed up locally automatically after every write operation to the path specified in the `vaultArchiveDir` property. The backup is a complete backup of the entire storage backend and not of any single values in the vault.

To restore the storage backend from a backup file:

1. Stop the vault service:

    ```bash
    $ /opt/vault/bin/vault-ctl.sh stop
    ```

1. Run the `restore` command, supplying the name of the backup file in the `vaultArchiveDir`:

    ```bash
    $ superintendent restore -file=<filename>
    ```

1. Restart the vault service:

    ```bash
    /opt/vault/bin/vault-ctl.sh start
    ```

1. Unseal the vault (see "Unseal Vault" above)

## How to deploy your project with Shipyard

### Ansible-based deployment
In the shipyard environment declaration, specify that the module is an ansible module and supply any required configuration (host inventory, vars, etc):
```json
{
  "name": "myProduct",
  "repository": "prod",
  "modules": [
     {
       "name": "myModule",
       "type": "ansible",
        "inventory": {
          "web-servers": {
            "hosts": [
              "hostname1",
              "hostname2",
            ],
            "vars": {
              "myVar": "myVal"
            },
          }
        }       
     }
  ]
}    
```

Shipyard will execute ansible-playbook against the `ansible/site.yml` file from your project.

### Terraform-based deployment
In the shipyard environment declaration, specify that the module is a terraform module, and supply 
any required configuration (including a configuration of an S3 bucket to hold remote state):

```json
{
  "name": "myProduct",
  "repository": "prod",
  "modules": [
     {
       "name": "myModule",
       "type": "terraform",
       "tf-state-bucket": "some-bucket",
       "vars": {
         "aws_region": "us-east-1",
         ...
       }
     }
  ]
}    
```

Shipyard will execute terraform against the `terraform` directory from your project.