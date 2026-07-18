# Установка и первый запуск

## Что нужно на компьютере

- **JDK 17+** и Android Studio (для Android/сервера).
- **Xcode 15+** (для iOS-сборки).
- **Aurora SDK** (только для Авроры) — SDK и симулятор ставятся с сайта Аврора ОС. Есть версия под
  Mac с M-чипами.

## Сервер

Сначала — сервер. Из корня репозитория:

```bash
./gradlew :server:run
```

Сервер поднимается на `0.0.0.0:8080`. Проверка: в браузере на том же компьютере откройте
`http://localhost:8080/` — должно быть «Ktor: Hello, Server!». Для оперативной проверки живости
используется `GET /api/ping` (возвращает `pong`).

## Android — эмулятор

```bash
./gradlew :androidApp:assembleDebug     # macOS/Linux
.\gradlew.bat :androidApp:assembleDebug  # Windows
```

Затем установить APK в эмулятор из Android Studio. Дополнительных настроек сети не нужно —
приложение автоматически стучится на `http://10.0.2.2:8080` (это адрес хоста, видимый из
Android-эмулятора). См. [network-setup.md](network-setup.md).

## iOS — симулятор

Открыть `apps/iosApp` в Xcode и нажать Run, или собрать фреймворк из терминала:

```bash
./gradlew :shared-ui:linkDebugFrameworkIosSimulatorArm64
```

На симуляторе сервер доступен как `http://127.0.0.1:8080`.

## Аврора — RPM

Aurora-вариант глобально переключает Compose-плагин на форк, поэтому собирается **отдельным
запуском** Gradle с флагом `-PbuildVariant=aurora`.

```bash
# только компиляция (без Aurora SDK)
./gradlew -PbuildVariant=aurora :auroraApp:compileKotlinLinuxX64

# RPM (init sysroot → link → RPM в Docker; нужен Aurora SDK)
./gradlew -PbuildVariant=aurora :auroraApp:buildReleasePipeline

# сборка + деплой + запуск на устройстве (нужен AURORA_DEVICE_IP, см. network-setup.md)
./gradlew -PbuildVariant=aurora :auroraApp:runReleaseOnDevice
```

## Реальные устройства (Android/iOS)

Эмулятор видит хост-машину без настроек; физическое устройство — нет. Нужно указать IP-адрес
компьютера в переменной `SERVER_IP` в `local.properties`. Подробно —
[network-setup.md](network-setup.md).
