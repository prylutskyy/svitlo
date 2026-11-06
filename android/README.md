# Android

Див. [README проєкту](../README.md).

Збірка та JVM-перевірки: `./build.ps1`. Інструментальні тести потребують емулятора або Android-пристрою. minSdk 26.

## Оптимізований APK

```powershell
.\build.ps1 -Tasks ':app:assembleRelease', ':app:lintRelease'
```

Release використовує R8, обфускацію та скорочення ресурсів. APK: `app/build/outputs/apk/release/app-release.apk`. Діагностичне зіставлення імен: `app/build/outputs/mapping/release/mapping.txt`.

Для сумісності з уже поширеними GitHub APK використовується наявний debug-ключ підпису. Сама release-збірка не є debuggable. Нова інсталяція з іншим ключем потребуватиме окремого плану міграції підпису. Ключі не зберігаються в репозиторії.
