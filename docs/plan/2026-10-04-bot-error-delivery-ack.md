# Подтверждение доставки ошибки Telegram

## Подтверждённый дефект

Новый main5ee41733 запускает background review после finalized, но обработчик
onError выставляет finalized=true, игнорируя false от finalizeStream либо
Optional.empty от standalone send. Caller пропускает fallback, а review может
появиться раньше недоставленного ответа. Две регрессии падают на реальном
новом исходнике; успешный send остаётся зелёным.

## Решение и границы

- Использовать фактическое acknowledgement обеих операций отправки.
- Сохранить полный errorText для существующего caller fallback, после cleanup
  draft-session. Failed delivery возвращать честно с streamFinalized=false.
- Failed terminal delivery без токенов считать обработанным ответом: не
  вызывать новую синхронную генерацию вместо повторной отправки готовой ошибки.
- Interrupt возвращает фактический результат финализации; отменённый turn
  остаётся под существующим caller cancellation guard.
- Сохранить API, права, настройки, polling intervals, scopes и миграции.

## Приёмка

Три focused сценария: failed edit/full error retention, failed no-token draft
send/no repeated model request, successful draft send. Полный bot/CLI gate,
оба configured coverage floors, production bot build и notices. Existing
первое сообщение проверяет реальный delayed scheduler и UUID/thread routing.
Это unit regression с mock transport; live Telegram/native acceptance не
утверждается. Любое исправление сбрасывает три полных ревью.
