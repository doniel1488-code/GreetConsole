# GreetConsole 1.1.0

- Полностью заменено окно Swing/AWT на отдельное приложение C# / Windows Forms.
- Fabric-мод больше не создаёт Java desktop window; это устраняет ошибку `java.awt.HeadlessException` в MineLauncher.
- Self-contained приложение Windows x64 встраивается в JAR и автоматически извлекается в `config/greetconsole`.
- C#-окно самостоятельно читает `logs/latest.log` в реальном времени.
- Сохранены поиск, фильтры уровней, пауза, очистка, копирование, автопрокрутка, Unicode, Minecraft legacy-коды, HEX и ANSI RGB / 256 цветов.
- Закрытие окна и повторный запуск управляются клавишей F8.

## GreetConsole 1.0.1

- Добавлена диагностика ошибок AWT/Swing и вызова F8.

## GreetConsole 1.0.0

- Первый релиз для Minecraft 1.21.8 / Fabric.
- Отдельное окно журнала Minecraft с чтением `logs/latest.log`.
