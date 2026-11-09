# Архитектура GreetConsole 1.1.0

## Компоненты

- `GreetConsoleClient` — Fabric client entrypoint. Регистрирует F8 и управляет внешним процессом.
- `companion/GreetConsole` — исходный код окна на C# / Windows Forms.
- `GreetConsole.exe` — self-contained приложение Windows x64, которое собирается в CI и включается в ресурсы JAR перед сборкой Fabric-мода.

## Запуск

1. Fabric-мод получает путь активной игры через Fabric Loader.
2. При нажатии F8 исполняемый файл извлекается в `config/greetconsole/GreetConsole.exe`.
3. Мод запускает файл отдельным процессом и передаёт полный путь к `logs/latest.log).
4. C#-приложение самостоятельно читает журнал с разрешённым совместным доступом к файлу, поэтому Minecraft может продолжать писать в него.
5. Повторное F8 завершает companion-процесс. Закрытие окна кнопкой X завершает приложение; следующий F8 запускает его снова.

## Почему окно не использует AWT

В некоторых лаунчерах Minecraft Java-процесс запускается в headless-окружении. В таком случае Swing не может создать `JFrame` и выбрасывает `java.awt.HeadlessException`. GreetConsole 1.1.0 не создаёт Java desktop UI: оконный интерфейс принадлежит отдельному Windows-процессу на C#.

## Сборка

GitHub Actions на Windows собирает приложение через .NET 8 SDK с параметрами self-contained / single-file, копирует EXE в ресурсы мода и затем собирает Fabric JAR через Gradle. Для пользователя .NET Runtime не требуется.
