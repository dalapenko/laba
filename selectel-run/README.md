# selectel-run

Bash-скрипт для запуска команд (например, инструментальных тестов Android) на устройствах [Selectel Mobile Farm](https://selectel.ru/services/mobile-testing/).

Скрипт арендует устройство по фильтрам (производитель, модель, версия Android), подключается к нему через ADB-over-TCP, выполняет произвольную команду и **всегда** освобождает устройство — даже при ошибке или Ctrl+C.

## Как это работает

```
Аутентификация → Генерация эфемерного ADB-ключа → Регистрация ключа
  → Аренда устройства (по фильтрам) → Получение serial + slot ID
  → Включение WiFi → Удалённое ADB-подключение → adb connect
  → Выполнение --cmd → Очистка (освобождение устройства, удаление ключа)
```

Шаг очистки регистрируется через `trap cleanup EXIT INT TERM` и выполняется при любом завершении скрипта. Освобождение устройства повторяется до 3 раз с интервалом 5 секунд, при необходимости используется fallback на API v1.

## Требования

| Инструмент | Установка                                                                                  |
|------------|--------------------------------------------------------------------------------------------|
| `bash` 4+  | macOS: `brew install bash`; Linux: предустановлен                                          |
| `curl`     | Предустановлен на macOS и всех CI-раннерах                                                 |
| `jq`       | `brew install jq` / `apt install jq`                                                       |
| `adb`      | [Android SDK Platform Tools](https://developer.android.com/studio/releases/platform-tools) |

## Установка

Скопировать `selectel-run.sh` в любое место на `$PATH`.

```bash
chmod +x selectel-run.sh
```


## Учётные данные

Передаются только через переменные окружения. **Никогда не передавайте их флагами** — они будут видны в списке процессов и логах CI.

| Переменная          | Описание                                    |
|---------------------|---------------------------------------------|
| `SELECTEL_USER`     | Имя пользователя / email аккаунта Selectel  |
| `SELECTEL_PASSWORD` | Пароль аккаунта                             |
| `SELECTEL_DOMAIN`   | Домен аккаунта, например `123456_myaccount` |
| `SELECTEL_PROJECT`  | Название проекта для получения токена       |

## Использование

```
./selectel-run.sh [OPTIONS]

Обязательные флаги:
  --manufacturer NAME   Производитель устройства (например, SAMSUNG, GOOGLE, INFINIX)
  --model NAME          Рыночное название (например, "Galaxy A34 5G")
  --version VER         Версия Android (например, 13)
  --cmd COMMAND         Команда для выполнения после подключения ADB

Необязательные флаги:
  --sdk LEVEL           Уровень SDK (например, 33); добавляется в фильтр, если указан
  --billing TYPE        Тип тарификации: minutes (по умолчанию) или hours
  --timeout SECS        Максимальное ожидание готовности ADB в секундах (по умолчанию: 300)
  --verbose             Выводить полные ответы API
  --dry-run             Показать что будет выполнено без реальных API-вызовов
  -h, --help            Показать справку
```

## Примеры

### Минимальный запуск

```bash
export SELECTEL_USER="da@example.com"
export SELECTEL_PASSWORD="secret"
export SELECTEL_DOMAIN="123456_myaccount"
export SELECTEL_PROJECT="myproject"

./selectel-run.sh \
  --manufacturer "SAMSUNG" \
  --model        "Galaxy A34 5G" \
  --version      "13" \
  --cmd          "./gradlew :app:connectedDebugAndroidTest"
```

### С фильтром по SDK и подробным выводом

```bash
./selectel-run.sh \
  --manufacturer "SAMSUNG" \
  --model        "Galaxy A34 5G" \
  --version      "13" \
  --sdk          "33" \
  --verbose \
  --cmd          "./gradlew :app:connectedDebugAndroidTest"
```

### Запуск конкретного тест-класса

```bash
./selectel-run.sh \
  --manufacturer "GOOGLE" \
  --model        "Pixel 7" \
  --version      "13" \
  --cmd          "./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.MyTest"
```

### Dry-run (без реальных API-вызовов)

```bash
./selectel-run.sh \
  --manufacturer "SAMSUNG" \
  --model        "Galaxy A34 5G" \
  --version      "13" \
  --cmd          "./gradlew :app:connectedDebugAndroidTest" \
  --dry-run
```

### Произвольная ADB-команда

```bash
./selectel-run.sh \
  --manufacturer "SAMSUNG" \
  --model        "Galaxy A34 5G" \
  --version      "13" \
  --cmd          "adb shell dumpsys battery"
```

## Интеграция с CI

### GitHub Actions

```yaml
jobs:
  android-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Install jq
        run: sudo apt-get install -y jq

      - name: Run tests on Selectel device
        env:
          SELECTEL_USER: ${{ secrets.SELECTEL_USER }}
          SELECTEL_PASSWORD: ${{ secrets.SELECTEL_PASSWORD }}
          SELECTEL_DOMAIN: ${{ secrets.SELECTEL_DOMAIN }}
          SELECTEL_PROJECT: ${{ secrets.SELECTEL_PROJECT }}
        run: |
          ./selectel-run.sh \
            --manufacturer "SAMSUNG" \
            --model        "Galaxy A34 5G" \
            --version      "13" \
            --cmd          "./gradlew :app:connectedDebugAndroidTest"
```

### GitLab CI

```yaml
android-test:
  image: ubuntu:22.04
  variables:
    SELECTEL_USER: $SELECTEL_USER
    SELECTEL_PASSWORD: $SELECTEL_PASSWORD
    SELECTEL_DOMAIN: $SELECTEL_DOMAIN
    SELECTEL_PROJECT: $SELECTEL_PROJECT
  before_script:
    - apt-get update && apt-get install -y curl jq android-tools-adb
  script:
    - ./selectel-run.sh
        --manufacturer "SAMSUNG"
        --model        "Galaxy A34 5G"
        --version      "13"
        --cmd          "./gradlew :app:connectedDebugAndroidTest"
```

## Коды завершения

| Код | Значение                                                            |
|-----|---------------------------------------------------------------------|
| `0` | Команда `--cmd` выполнена успешно                                   |
| `1` | Ошибка скрипта (аутентификация, нет устройства, таймаут ADB и т.д.) |
| `N` | Код завершения команды `--cmd`                                      |

CI-пайплайн получает код завершения команды напрямую — провал тестов автоматически проваливает сборку.

## Безопасность биллинга

Скрипт разработан так, чтобы арендованное устройство не осталось "висеть" бесконечно:

- `trap cleanup EXIT INT TERM` выполняется при любом завершении, включая ошибки `set -e` и Ctrl+C
- Освобождение устройства повторяется до 3 раз с интервалом 5 секунд
- Если освобождение через API v3 не удалось (slot ID неизвестен), используется fallback на API v1
- Если все попытки провалились — в stderr выводится предупреждение с serial и slot ID для ручного освобождения через консоль Selectel

## Справочник API

Скрипт использует Selectel Mobile Farm API v3 там, где доступно, иначе v1:

| Операция                              | Эндпоинт                                                     |
|---------------------------------------|--------------------------------------------------------------|
| Аутентификация                        | `POST https://cloud.api.selcloud.ru/identity/v3/auth/tokens` |
| Регистрация ADB-ключа                 | `POST /mobfarm/api/v2/keys/adb`                              |
| Удаление ADB-ключа                    | `DELETE /mobfarm/api/v2/keys/adb/{fingerprint}`              |
| Аренда устройства                     | `POST /mobfarm/api/v3/devices`                               |
| Список устройств проекта              | `GET /mobfarm/api/v3/devices`                                |
| Включение WiFi                        | `PATCH /mobfarm/api/v3/devices/{serial}/settings`            |
| Удалённое подключение                 | `POST /mobfarm/api/v1/user/devices/{serial}/remoteConnect`   |
| Освобождение устройства (v3)          | `DELETE /mobfarm/api/v3/devices/{serial}`                    |
| Освобождение устройства (v1 fallback) | `DELETE /mobfarm/api/v1/user/devices/{serial}`               |

## Стратегия ADB-ключа

Для каждого запуска генерируется эфемерная пара RSA-ключей в `/tmp/selectel_adb_<PID>` с помощью `adb keygen`. Публичный ключ регистрируется в Selectel перед запуском и удаляется в шаге очистки. Суффикс PID предотвращает конфликты при параллельных запусках на одной машине.
