# MyLunarCore Spring Cloud Alibaba Scaffold

This directory is the microservice migration scaffold and does not replace the current monolith yet.

## Included modules

- `common/common-api`
- `services/api-gateway`
- `services/auth-service`
- `services/player-service`

## Run infrastructure

```bash
docker compose -f microservices/docker-compose.yml up -d
```

## Build services

```bash
cd microservices
mvn -U clean package
```

## Start order

1. `auth-service`
2. `player-service`
3. `api-gateway`

After startup (gateway HTTPS mock):

- `GET https://localhost:18443/auth/health`
- `GET https://localhost:18443/players/10001`

## Next migration targets

- Add `item-service`, `battle-service`, `scene-service`, `rogue-service`, `gacha-service`
- Add OpenFeign and service-level auth
- Move config to Nacos Data IDs per service and environment
