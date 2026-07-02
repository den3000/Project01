# :agenticHubClient:platform:greeting — демо-домен приветствия

KMP-модуль (jvm/android/ios): маленькая доменная сущность для Compose-демо (`features:composeApp`).
Выделен из бывшего `:shared`, чтобы UI-модуль зависел на неё по DI (`App(greeting: Greeting)`), а не
создавал внутри.

## Публичный API
- `Greeting` (`greet()` → строка с именем платформы), `sayHello(name)` (`GreetingUtil.kt`),
  `Platform`/`expect getPlatform()` + actuals (`Platform.{jvm,android,ios}.kt`).

## Зависимости
Нет модульных (лист). Потребители — `features:composeApp`, `apps:desktopApp`, `apps:androidApp`
(строят `Greeting()` и передают в `App`).

## Тесты
`./gradlew :agenticHubClient:platform:greeting:jvmTest` — `GreetingTest` (`sayHello` + `greet`).
Имена тест-функций **без** `()`/`,` — иначе iOS-таргет (commonTest) не компилится (ограничение
Kotlin/Native на backtick-имена).
