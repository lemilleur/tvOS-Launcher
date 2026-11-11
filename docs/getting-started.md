# Сборка и запуск

## Инструменты

| Инструмент | Версия |
|---|---|
| JDK | 17 или новее |
| Android SDK Platform | `android-34` |
| Android SDK Build Tools | `34.0.0` |
| Android SDK Platform Tools | Нужны для установки и отладки через `adb` |
| Устройство или эмулятор | Android TV или Google TV с API 24 и новее |

Android SDK можно установить через SDK Manager. Укажите каталог SDK в `ANDROID_HOME`, если он отличается от стандартного пути. Укажите `JAVA_HOME` или добавьте `javac` в `PATH`.

```sh
git clone https://github.com/lemilleur/tvOS-Launcher.git
cd tvOS-Launcher
```

## Windows

Из PowerShell в каталоге проекта выполните:

```powershell
.\build.ps1 -ForkPackage
```

`-ForkPackage` собирает APK с идентификатором `com.techgenius.tvoslauncher.fork`. `-TestOnTv` использует тот же идентификатор и включает отладку приложения. Без этих параметров используется идентификатор оригинального проекта.

## macOS и Linux

```sh
./build.sh
```

Сценарий Bash собирает пакет оригинального проекта. При необходимости задайте номер и имя версии через `VERSION_CODE` и `VERSION_NAME`. Оба сценария используют `aapt2`, `javac --release 11`, `d8`, `zipalign` и `apksigner`. Результат находится в `build/tvos-home.apk`.

## Запуск

```sh
adb install -r build/tvos-home.apk
adb shell am start -n com.techgenius.tvoslauncher.fork/com.techgenius.tvoslauncher.MainActivity
```

В примере запускается пакет форка. Для обычной сборки укажите компонент `com.techgenius.tvoslauncher/.MainActivity`. Подключение телевизора по сети описано в [инструкции по установке](install.md).

В эмуляторе создайте устройство Android TV с системным образом под архитектуру компьютера. Проверку HDMI, пульта и скорости отрисовки проводите на реальном устройстве.

## Сокращённые команды

В примерах ниже используется обычная сборка. Для форка замените компонент на `com.techgenius.tvoslauncher.fork/com.techgenius.tvoslauncher.MainActivity`.

| Команда | Что открывает |
|---|---|
| `adb shell am start -n com.techgenius.tvoslauncher/.MainActivity --es panel cc` | Панель управления |
| `adb shell am start -n com.techgenius.tvoslauncher/.MainActivity --es panel weather` | Полный прогноз |
| `adb shell am start -n com.techgenius.tvoslauncher/.MainActivity --es panel inputs` | Источники сигнала |
| `adb shell am start -n com.techgenius.tvoslauncher/.MainActivity --es panel present` | Презентацию |
| `adb shell am start -n com.techgenius.tvoslauncher/.MainActivity --es panel search` | Поиск |
| `adb shell dumpsys gfxinfo com.techgenius.tvoslauncher` | Статистику отрисовки |

Снимок экрана сохраняется командой `adb exec-out screencap -p > shot.png`. Ошибки приложения видны в `adb logcat -s TvHome AndroidRuntime`.

## Если сборка или установка не работает

| Проблема | Действие |
|---|---|
| Отсутствует `aapt2` или `android.jar` | Установите Build Tools 34.0.0 и Platform 34. Проверьте `ANDROID_HOME`. |
| Не найден `javac` | Установите JDK 17+ и проверьте `JAVA_HOME` или `PATH`. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Соберите APK с ключом, которым подписана текущая установка. |
| `device offline` или `unauthorized` | Переподключитесь и подтвердите отладку на телевизоре. |

[Как внести изменения](../CONTRIBUTING.md).
