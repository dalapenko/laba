# Проблема

При запуске benchmark теста на удаленном устройстве в Selectel ферме - ошибка:

```
Test execution failed on test driver <com.google.testing.platform.runtime.android.driver.AndroidInstrumentationDriver>!Test failures could be due to various issues like incorrectly configuring the driver, timeouts, incorrect test environment etc. Check logs for more info.
Connection reset
java.net.SocketException: Connection reset
```

# Воспроизведение

1) Арендовать устройство - строго Pixel не ниже 6 модели (требование Google). Я тестировал на Pixel 8 (Android 14).
2) Подключиться по ADB
3) Запустить `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`

# Фактический результат

Тесты проходят, но gradle task завершается с ошибкой

```
Test execution failed on test driver <com.google.testing.platform.runtime.android.driver.AndroidInstrumentationDriver>!Test failures could be due to various issues like incorrectly configuring the driver, timeouts, incorrect test environment etc. Check logs for more info.
Connection reset
java.net.SocketException: Connection reset
at java.base/sun.nio.ch.SocketChannelImpl.throwConnectionReset(SocketChannelImpl.java:401)
at java.base/sun.nio.ch.SocketChannelImpl.read(SocketChannelImpl.java:434)
at com.android.ddmlib.internal.DeviceImpl.lambda$executeRemoteCommand$18(DeviceImpl.java:872)
at com.android.ddmlib.internal.DeviceImpl.logRun1(DeviceImpl.java:1801)
at com.android.ddmlib.internal.DeviceImpl.executeRemoteCommand(DeviceImpl.java:755)
at com.android.ddmlib.internal.DeviceImpl.lambda$executeRemoteCommand$15(DeviceImpl.java:618)
at com.android.ddmlib.internal.DeviceImpl.logRun1(DeviceImpl.java:1801)
at com.android.ddmlib.internal.DeviceImpl.executeRemoteCommand(DeviceImpl.java:615)
at com.android.ddmlib.internal.DeviceImpl.lambda$executeShellCommand$14(DeviceImpl.java:573)
at com.android.ddmlib.internal.DeviceImpl.logRun1(DeviceImpl.java:1801)
at com.android.ddmlib.internal.DeviceImpl.executeShellCommand(DeviceImpl.java:570)
at com.android.tools.utp.plugins.deviceprovider.ddmlib.DdmlibAndroidDevice.executeShellCommand(DdmlibAndroidDevice.kt)
at com.android.tools.utp.plugins.deviceprovider.ddmlib.DdmlibAndroidDeviceController$executeAsync$deferred$1.invokeSuspend(DdmlibAndroidDeviceController.kt:172)
at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:108)
at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
at java.base/java.lang.Thread.run(Thread.java:1583)
```

# Ожидаемый результ

Нет ошибок, бенчмарк успешно завершается согласно результатам тестов


# Примечания

Для упрощения тестирования сделал скрипт в `selectel-run.sh`.
Для запуска необходимо указать переменные окружения с кредам:

| Переменная          | Описание                                    |
|---------------------|---------------------------------------------|
| `SELECTEL_USER`     | Имя пользователя / email аккаунта Selectel  |
| `SELECTEL_PASSWORD` | Пароль аккаунта                             |
| `SELECTEL_DOMAIN`   | Домен аккаунта, например `123456_myaccount` |
| `SELECTEL_PROJECT`  | Название проекта для получения токена       |

затем выполнить из корня проекта:
`./selectel-run/selectel-run.sh --manufacturer "GOOGLE" --model "Pixel 8" --version "14" --sdk "34" --cmd "./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest --info"`
(прим. не забыть выдать права на исполнение `chmod +x ./selectel-run/selectel-run.sh`)
Подробнее о скрипте можно прочитать [selectel-run/README.md](./README.md)

