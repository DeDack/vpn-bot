# Развёртывание Remnawave и подключение нод

Эта инструкция описывает самостоятельную установку Remnawave Panel, страницы подписки и Remnawave Node. Все адреса условные: замените домены `example.com` своими. Не сохраняйте токены, пароли, UUID и приватные ключи в Git.

Используйте такую схему:

```text
panel.example.com -> reverse proxy -> Remnawave Panel
sub.example.com   -> reverse proxy -> Remnawave Subscription Page
Remnawave Panel   -> node-01.example.com:NODE_PORT -> Remnawave Node / Xray
```

Панель и ноду желательно размещать на разных серверах. Для панели рекомендуется от 4 ГБ RAM и 4 CPU, для ноды — от 1 ГБ RAM и 1 CPU. На обоих серверах нужны актуальные Docker Engine и Docker Compose Plugin.

## 1. DNS и сетевой доступ

Создайте DNS-записи для:

- `panel.example.com` — административная панель;
- `sub.example.com` — страница подписки;
- `node-01.example.com` — VPN-нода.

Панель публикуйте через HTTPS на отдельном домене, а не в подпути. PostgreSQL, Redis и внутренние порты приложений не открывайте в интернет. Порт управления нодой (`NODE_PORT`) разрешите только со стороны сервера панели. VPN-inbound порты открывайте отдельно в соответствии с выбранным Config Profile.

## 2. Установка Remnawave Panel

На сервере панели создайте каталог и скачайте актуальные официальные файлы:

```bash
sudo mkdir -p /opt/remnawave
cd /opt/remnawave
sudo curl -L -o docker-compose.yml \
  https://raw.githubusercontent.com/remnawave/backend/main/docker-compose-prod.yml
sudo curl -L -o .env \
  https://raw.githubusercontent.com/remnawave/backend/main/.env.sample
```

Сгенерируйте независимые секреты:

```bash
openssl rand -hex 64
openssl rand -hex 64
openssl rand -hex 64
openssl rand -base64 48
```

Откройте `/opt/remnawave/.env` и задайте:

- случайные `APP_SECRET`, `METRICS_PASS` и `WEBHOOK_SECRET_HEADER`;
- отдельный сильный пароль PostgreSQL и тот же пароль в `DATABASE_URL`;
- `FRONT_END_DOMAIN=panel.example.com`;
- `SUB_PUBLIC_DOMAIN=sub.example.com`;
- остальные обязательные значения по комментариям актуального `.env.sample`.

Создайте внешнюю Docker-сеть, которую используют панель и reverse proxy:

```bash
sudo docker network inspect remnawave-network >/dev/null 2>&1 || \
  sudo docker network create remnawave-network
```

Проверьте итоговую конфигурацию и запустите стек:

```bash
cd /opt/remnawave
sudo docker compose config --quiet
sudo docker compose up -d
sudo docker compose ps
sudo docker compose logs --tail=200
```

## 3. HTTPS через Caddy

Подключите Caddy к `remnawave-network`. Минимальный фрагмент `Caddyfile` для панели:

```caddyfile
panel.example.com {
    reverse_proxy remnawave:3000
}
```

Caddy должен принимать входящие соединения на 80/443 и автоматически выпустить TLS-сертификат. Не публикуйте порт `3000` напрямую. После запуска откройте `https://panel.example.com`, создайте первого администратора и сразу сохраните резервные коды доступа в защищённом хранилище.

## 4. Subscription Page

Создайте API-токен в Remnawave с минимально необходимыми правами, затем на сервере панели подготовьте отдельный compose:

```bash
sudo mkdir -p /opt/remnawave/subscription
cd /opt/remnawave/subscription
```

`docker-compose.yml`:

```yaml
services:
  remnawave-subscription-page:
    image: remnawave/subscription-page:latest
    container_name: remnawave-subscription-page
    restart: always
    env_file:
      - .env
    ports:
      - "127.0.0.1:3010:3010"
    networks:
      - remnawave-network

networks:
  remnawave-network:
    external: true
```

`.env`:

```dotenv
APP_PORT=3010
REMNAWAVE_PANEL_URL=http://remnawave:3000
REMNAWAVE_API_TOKEN=replace-with-generated-api-token
TRUST_PROXY=1
```

Не коммитьте этот `.env`. Запустите страницу:

```bash
sudo docker compose config --quiet
sudo docker compose up -d
sudo docker compose logs --tail=100
```

Добавьте в `Caddyfile`:

```caddyfile
sub.example.com {
    reverse_proxy remnawave-subscription-page:3010
}
```

Перезапустите Caddy и проверьте HTTPS. В панели значение `SUB_PUBLIC_DOMAIN` должно совпадать с этим доменом.

## 5. Подключение Remnawave Node

В панели откройте **Nodes → Management → Add node**:

1. Укажите понятное имя, `node-01.example.com` и выбранный `NODE_PORT`.
2. Нажмите **Copy docker-compose.yml**. Используйте именно compose, сформированный панелью: в нём уже есть необходимые ключи связи.
3. На сервере ноды создайте каталог:

   ```bash
   sudo mkdir -p /opt/remnanode
   cd /opt/remnanode
   ```

4. Сохраните сгенерированный compose как `/opt/remnanode/docker-compose.yml` и запустите:

   ```bash
   sudo docker compose config --quiet
   sudo docker compose up -d
   sudo docker compose logs -f -t
   ```

5. Вернитесь в панель, завершите добавление и назначьте ноде Config Profile.

Не копируйте compose ноды между публичными репозиториями: он содержит данные, привязывающие ноду к панели.

## 6. Config Profile, Host и Squad

После подключения ноды:

1. Создайте **Config Profile** и загрузите подходящий официальный шаблон.
2. Настройте inbound, транспорт и порт; затем назначьте профиль ноде.
3. Создайте **Host** для каждого пользовательского адреса подключения. В `Address` используйте DNS-имя, а не жёстко заданный адрес сервера.
4. Добавьте Host и пользователей в нужный **Internal Squad**.
5. Создайте тестового пользователя, откройте его subscription URL и проверьте подключение с тестового устройства.

## 7. CDN, маршрутизация и сети с ограниченным доступом

Используйте эти возможности только в соответствии с законодательством вашей страны и правилами провайдера/CDN.

- CDN подходит только для совместимых HTTP-транспортов, например WebSocket или gRPC, если конкретный CDN их поддерживает. Произвольный TCP/UDP-трафик или REALITY нельзя автоматически спрятать за обычным CDN-прокси.
- Создавайте отдельные Host/inbound для прямого подключения, CDN-маршрута и резервного маршрута. Так отказ одного пути не затронет остальные.
- Для CDN используйте отдельный нейтральный домен, корректные TLS-настройки и origin, закрытый от прямого публичного доступа там, где это поддерживается.
- Для сетей с «белыми списками» заранее проверяйте доступность домена, TLS/SNI, транспорт и ограничения оператора. Не считайте один CDN универсальным способом обхода фильтрации.
- Российские сайты, банки и маркетплейсы можно направлять напрямую правилами клиента по `geoip/geosite`, чтобы они открывались с локального адреса при включённом VPN.
- Если требуется серверная маршрутизация, Remnawave позволяет направлять выбранные домены напрямую, а остальной трафик — через bridge-ноду. Официальный пример server-side routing является демонстрационным: адаптируйте правила и протестируйте их до production.
- Пользовательский Host должен ссылаться на домен, который действительно ведёт к нужному inbound. Серверные routing rules не меняют соединения, отправленные клиентом через `DIRECT`.

Не храните в публичном репозитории реальные origin-домены, адреса нод, UUID, Reality private keys, short IDs, API-токены и firewall allowlist.

## 8. Проверка после установки

Минимальный smoke-check:

```bash
sudo docker compose -f /opt/remnawave/docker-compose.yml ps
sudo docker compose -f /opt/remnawave/docker-compose.yml logs --tail=200
sudo docker compose -f /opt/remnanode/docker-compose.yml ps
sudo docker exec remnanode xlogs
```

Дополнительно проверьте:

- вход в панель по HTTPS;
- доступность Subscription Page;
- статус ноды в панели;
- выдачу subscription URL тестовому пользователю;
- импорт подписки в клиент;
- подключение ко всем опубликованным Host;
- DNS и маршрутизацию локальных/зарубежных ресурсов;
- отсутствие открытых наружу PostgreSQL, Redis и внутренних портов приложений.

## 9. Обновление и резервное копирование

Перед обновлением сохраните `.env`, compose-файлы и полный дамп PostgreSQL в закрытом хранилище. Обновляйте компоненты в порядке: Panel, Node, Subscription Page.

Для каждого каталога:

```bash
sudo docker compose pull
sudo docker compose down
sudo docker compose up -d
sudo docker compose logs --tail=200
```

После обновления повторите smoke-check. Не используйте `latest` без предварительной проверки release notes и возможности вернуть предыдущий image tag.

## Теги для GitHub

```text
remnawave, remnawave-node, xray, vless, reality, websocket, grpc,
cdn, whitelist-bypass, anti-censorship, geo-routing, vpn-panel,
subscription-page, docker, docker-compose, caddy, postgresql
```

## Официальная документация

- [Requirements](https://docs.rw/install/requirements/)
- [Remnawave Panel](https://docs.rw/install/remnawave-panel/)
- [Caddy](https://docs.rw/install/reverse-proxies/caddy/)
- [Bundled Subscription Page](https://docs.rw/install/subscription-page/bundled/)
- [Remnawave Node](https://docs.rw/install/remnawave-node/)
- [Quick Start](https://docs.rw/learn-en/quick-start/)
- [Config Profiles](https://docs.rw/learn-en/config-profiles/)
- [Hosts](https://docs.rw/learn-en/hosts/)
- [Server-side routing](https://docs.rw/learn-en/server-routing/)
- [Upgrading](https://docs.rw/install/upgrading/)
