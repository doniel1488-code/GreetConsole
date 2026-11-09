# GreetConsole 1.1.0 — Minecraft 1.21.8 (Fabric)

![Иконка GreetConsole](src/main/resources/assets/greetconsole/icon.png)

GreetConsole — клиентский Fabric-мод с отдельным окном журнала Minecraft. Само окно написано на **C# / Windows Forms** и работает отдельным Windows-процессом. Оно не использует AWT/Swing и не зависит от графического окружения Java.

## Как открыть консоль

1. Установи JAR GreetConsole в папку `.minecraft/mods` активного экземпляра.
2. Запусти Minecraft Java Edition 1.21.8 с Fabric.
3. Нажми **F8** — мод извлечёт встроенное приложение в `config/greetconsole` и запустит отдельное окно.
4. Повторное нажатие **F8** закроет окно. Кнопка **X** также закрывает приложение; следующее нажатие F8 запустит его снова.

Клавишу можно изменить в **Настройки → Управление → Назначение клавиш → GreetConsole → Открыть GreetConsole**.

## Что умеет консоль

- Самостоятельно читает `logs/latest.log` активного экземпляра Minecraft, включая нестандартные папки лаунчеров.
- Показывает последние строки и подхватывает новые записи в реальном времени.
- Хранит в памяти до 5 000 строк.
- Фильтры TRACE, DEBUG, INFO, WARN, ERROR, FATAL и OTHER.
- Поиск с подсветкой, пауза, очистка, копирование и автопрокрутка.
- UTF-8, кириллица, латиница и Unicode; отображение зависит от шрифтов Windows.

### Цвета и форматирование

- Стандартные цвета Minecraft: `&0`–`&f` и `§0`–`§f`.
- Форматирование: `&l` (жирный), `&o` (курсив), `&n` (подчёркивание), `&m` (зачёркивание), `&r` (сброс).
- HEX: `&#RRGGBB`, например `&#FF5500`.
- Расширенный HEX: `&x&F&F&5&5&0&0` и вариант с символом `§`.
- ANSI SGR, включая RGB (24-bit) и палитру из 256 цветов.

Исходный `latest.log` мод и приложение не изменяют. Код `&k` распознаётся, но обфускация намеренно не применяется — строки журнала остаются читаемыми.

## Установка

Требуются Minecraft Java Edition **1.21.8**, **Java 21**, **Fabric Loader** и **Fabric API** для Minecraft 1.21.8.

Полезные ссылки:

- [Официальный установщик Fabric Loader](https://fabricmc.net/use/installer/)
- [Fabric API для Minecraft 1.21.8 на Modrinth](https://modrinth.com/mod/fabric-api/versions?g=1.21.8&l=fabric)
- [Java 21 — Eclipse Temurin](https://adoptium.net/temurin/releases/?version=21)
- [Официальная инструкция по установке модов Fabric](https://docs.fabricmc.net/players/installing-mods)

Просто помести JAR GreetConsole и Fabric API в `mods`. Отдельно устанавливать .NET или запускать установщик C#-приложения не нужно: self-contained приложение Windows x64 уже встроено в JAR и извлекается при первом нажатии F8.

## Если окно не появляется

1. Убедись, что в списке модов есть `greetconsole` и Minecraft запущен с профилем Fabric 1.21.8.
2. Нажми F8 и проверь `logs/latest.log`. Мод записывает запуск приложения или подробную ошибку извлечения/запуска.
3. Проверь, появился ли файл `config/greetconsole/GreetConsole.exe`.
4. Если окно не запустилось, пришли строки `GreetConsole` из `latest.log`.

## Сборка из исходников (Windows)

Нужны Java 21, .NET 8 SDK и интернет-доступ. Запусти `build.bat`: скрипт соберёт self-contained приложение C# и затем Fabric JAR. Итоговый файл появится в `build/libs/greetconsole-1.1.0.jar`.

GitHub Actions собирает мод на Windows и публикует JAR с уже встроенным C#-приложением. Релизы находятся во вкладке [GitHub Releases](https://github.com/doniel1488-code/GreetConsole/releases).

## Лицензия

MIT.
