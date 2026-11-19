# TG WS Proxy для Android

Нативный Android-клиент (порт [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)) для проксирования трафика Telegram через WebSocket.

## Сборка и запуск

1. Откройте папку проекта в **Android Studio** (Ladybug или новее).
2. Дождитесь синхронизации Gradle (требуется Gradle 8.9+, wrapper подтянется автоматически).
3. Нажмите **Run** для установки на устройство или эмулятор.

## Возможности

- **MTProto obfuscation:** handshake, relay init, 4 потока AES-CTR (`core/Mtproto.kt`).
- **WebSocket-мост:** подключение к `kwsN.web.telegram.org` через IP из настроек (`core/WsClient.kt`, `ProxyServer.kt`).
- **Отказоустойчивость:** `MsgSplitter`, чёрный список ДЦ, экспоненциальные паузы после ошибок, запасные пути (пользовательские CF-домены `kwsN.<домен>` и прямой TCP).
- **Интерфейс и фон:** Foreground-сервис с кнопкой отключения в уведомлении, экраны *Главная*, *Настройки* и *Журнал*.

## Ограничения текущей версии

Пока не реализованы: Fake TLS и маскировка, PROXY protocol, пул прогретых WS-соединений, CF worker, HTTP/2-канал для CF, автозагрузка списка CF-доменов с GitHub и проверка обновлений.
