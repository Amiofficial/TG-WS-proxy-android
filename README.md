# TG WS Proxy для Android (нативный порт Flowseal/tg-ws-proxy)

Открыть папку в Android Studio (Ladybug или новее), дождаться синхронизации Gradle, Run.
Wrapper (gradlew) Android Studio создаст сам; нужен Gradle 8.9+.

## Что портировано
- MTProto obfuscation: handshake, relay init, 4 потока AES-CTR (core/Mtproto.kt)
- WebSocket-мост к kwsN.web.telegram.org через IP из настроек (core/WsClient.kt, ProxyServer.kt)
- MsgSplitter, чёрный список DC, паузы после ошибок
- Запасные пути: CF-домены пользователя (kwsN.<домен>) и прямой TCP с экспоненциальной паузой
- Foreground-сервис, уведомление с кнопкой «Выключить», экраны Главная / Настройки / Журнал

## Не портировано (v1)
Fake TLS и маскировка, PROXY protocol, пул прогретых WS-соединений, CF worker,
HTTP/2-канал для CF, автозагрузка списка CF-доменов с GitHub, проверка обновлений.

## Не проверено
Код не компилировался и не запускался в среде, где он писался. Первым делом собрать
и сверить handshake с реальным Telegram.
