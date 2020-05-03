# Mimir
[Mimir](https://en.wikipedia.org/wiki/M%C3%ADmir)


## Prerequisites

* AWS CLI - https://docs.aws.amazon.com/cli/latest/userguide/install-cliv2.html
* Docker Compose - https://docs.docker.com/compose/install
* git - https://git-scm.com/book/en/v2/Getting-Started-Installing-Git
* AWS Account with Organizations configured

## Clone the repo
```console
$ git clone https://github.com/openraven/aws-config-discovery
```

## Setup AWS permissions and services

### Deploy permissions

    SourceAccountId = {AccountId where Open Raven will be executing from or having account credentials associated to}

To reveal the configured credentials accountId:
```console
$ aws sts get-caller-identity
```


1. Deploy with cloud formation stack sets to accounts/ous from organization.

    * template from - https://openraven-deploy.s3.amazonaws.com/assume-role-stack-set.yaml

2. Deploy with cloud formation to root organization account.

    * template from - https://openraven-deploy.s3.amazonaws.com/org-readonly-role-stack.yaml

### Deploy service configurations (choose your own adventure)

Config service must be enabled in every region and account that resource discovery is to be performed.

Configure a nightly snapshot to be delivered to an S3 bucket, mimir will only ingest snapshots from the default snapshot delivery channel.

* Setup AWS ConfigService
https://docs.aws.amazon.com/config/latest/developerguide/gs-cli.html

or

* Deploy AWS ConfigService via cloud formation stacksets
https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/stacksets-getting-started.html

## Setup elastic search

```console
$ docker-compose -f docker-compose.yml up
```

## Build and run from source

```console
$ mvn spring-boot:run -Dspring-boot.run.profiles="default, local"
```

## Usage (resource discovery)

In a browser navigate to:
http://localhost:8080/swagger-ui.html

1. Execute `/mimir/organization_info`
2. Execute `/mimir/config_for_account`
3. Execute `/mimir/ingest_from_snapshot`

Upon completion of the above tasks data should be populated throughout elastic search in aws* indices.

Happy data spelunking.

## Launch kibana

```console
$ open http://localhost:5601
```
