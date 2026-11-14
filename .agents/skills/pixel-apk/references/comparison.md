# Порівняння з Android Skills

| Скіл | Сильна сторона | Що взяв pixel-apk |
|---|---|---|
| AGP 9 Upgrade | [docs] Міграція на вбудований Kotlin і новий DSL | Перевірка версій і відсутності kotlin-android. Для нового проєкту використовуємо CLI. |
| Glance updates and memory | [docs] Оновлення стану, бюджет RemoteViews, R8 і пошук причин застарілого UI | Читати для Glance. Перевіряти всі розміри, оновлення після зміни стану і наявність резервного віджета без Bitmap. |
| AppFunctions | [docs] Анотації, метадані, KSP, перевірка adb | Читати лише для опційного модуля. Перевіряти згенеровані метадані й підтримку на пристрої. |

[аналіз] pixel-apk додає правила саме для твоїх апок: один екран, M3 Expressive, Windows CLI, спільний сертифікат, префікс релізу для Obtainium та перевірки перед доставкою. Офіційні скіли мають глибші приклади для своїх окремих задач. Їхні актуальні джерела варто читати за потреби.

- https://github.com/android/skills/blob/main/build-system/agp/agp-9-upgrade/SKILL.md
- https://github.com/android/skills/blob/main/system/glance-appwidget-updates-and-memory/SKILL.md
- https://github.com/android/skills/blob/main/device-ai/appfunctions/SKILL.md

